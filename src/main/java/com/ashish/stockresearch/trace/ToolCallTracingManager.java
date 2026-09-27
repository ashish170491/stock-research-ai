package com.ashish.stockresearch.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps the real {@link ToolCallingManager} to log the two halves of every
 * tool round trip: what the model asked for, and what it got back.
 *
 * This sits at the one point where that data actually crosses - Spring AI
 * runs the tool loop inside the chat model, so an advisor around
 * {@code ChatClient.call()} only ever sees the first request and the final
 * answer. Everything in between passes through here.
 *
 * It decorates rather than replaces: the delegate still does all the work,
 * including argument binding, execution and exception handling, so turning
 * the trace off changes nothing about behaviour.
 */
class ToolCallTracingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger("com.ashish.stockresearch.trace.Trace");

    private final ToolCallingManager delegate;
    private final TraceProperties properties;

    ToolCallTracingManager(ToolCallingManager delegate, TraceProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        List<ToolDefinition> definitions = delegate.resolveToolDefinitions(chatOptions);
        if (properties.enabled() && log.isDebugEnabled()) {
            log.debug("tools offered to the model: {}", definitions.stream().map(ToolDefinition::name).toList());
        }
        return definitions;
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        if (!properties.enabled()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        ConversationTrace trace = TraceContext.current().orElse(null);
        String id = trace != null ? trace.id() : "------";
        int round = trace != null ? trace.nextModelRound() : 0;

        List<AssistantMessage.ToolCall> requested = requestedToolCalls(chatResponse);
        if (trace != null) {
            trace.countToolCalls(requested.size());
        }

        logRequestedCalls(id, round, chatResponse, requested);

        long startNanos = System.nanoTime();
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        logResults(id, round, seconds, requested, result);
        return result;
    }

    private List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = chatResponse.getResult().getOutput().getToolCalls();
        return toolCalls != null ? toolCalls : List.of();
    }

    private void logRequestedCalls(String id, int round, ChatResponse chatResponse,
                                   List<AssistantMessage.ToolCall> requested) {
        log.info("[{}] round {} > model requested {} tool call(s)", id, round, requested.size());
        for (AssistantMessage.ToolCall call : requested) {
            log.info("[{}]   --> {}({})", id, call.name(), Payloads.oneLine(call.arguments(), properties));
        }

        // The model's own words for this round. Usually empty when it is
        // calling a tool, but reasoning models put their deliberation here -
        // which is exactly what explains why this tool was chosen.
        String text = chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;
        if (text != null && !text.isBlank()) {
            log.debug("[{}]   model reasoning before the call: {}", id, Payloads.full(text));
        }
    }

    /**
     * Logs the payload handed back to the model for each call. These are the
     * exact JSON strings the model will read, so a wrong answer can always be
     * traced to either bad data here or a bad reading of good data.
     *
     * {@code conversationHistory()} accumulates every round, so responses are
     * matched to the ids requested in this round - otherwise round two would
     * reprint round one's results and the trace would misrepresent the flow.
     */
    private void logResults(String id, int round, double seconds,
                            List<AssistantMessage.ToolCall> requested, ToolExecutionResult result) {
        log.info("[{}] round {} < tool results in {}s", id, round, format(seconds));

        if (result == null || result.conversationHistory() == null) {
            return;
        }

        Map<String, ToolResponseMessage.ToolResponse> byId = new LinkedHashMap<>();
        for (Message message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    byId.put(response.id(), response);
                }
            }
        }

        for (AssistantMessage.ToolCall call : requested) {
            ToolResponseMessage.ToolResponse response = byId.get(call.id());
            if (response == null) {
                log.info("[{}]   <-- {} produced no response message", id, call.name());
                continue;
            }
            String data = response.responseData() != null ? response.responseData() : "";
            log.info("[{}]   <-- {} [{} chars] {}", id, call.name(), data.length(),
                    Payloads.oneLine(data, properties));
            log.debug("[{}]   <-- {} full payload: {}", id, call.name(), Payloads.full(data));
        }
    }

    private String format(double seconds) {
        return String.format("%.2f", seconds);
    }
}
