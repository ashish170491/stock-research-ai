package com.ashish.stockresearch.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Opens a trace for each question and closes it with the answer, so the
 * tool-level lines from {@link ToolCallTracingManager} sit between a clear
 * start and end rather than scrolling past unattributed.
 *
 * It deliberately does not try to log the tool round trips itself. Spring AI
 * executes the tool loop inside the chat model, so by the time this advisor
 * sees a response every tool has already run - the middle of the flow is
 * only visible from the tool-calling manager.
 */
class ConversationTraceAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger("com.ashish.stockresearch.trace.Trace");
    private static final String RULE = "=".repeat(78);

    private final TraceProperties properties;

    ConversationTraceAdvisor(TraceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "conversation-trace";
    }

    @Override
    public int getOrder() {
        // Outermost, so the trace spans everything the other advisors do.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!properties.enabled()) {
            return chain.nextCall(request);
        }

        ConversationTrace trace = TraceContext.begin();
        try {
            log.info(RULE);
            log.info("[{}] USER > {}", trace.id(), userText(request));
            log.debug("[{}] full prompt sent to the model: {}", trace.id(),
                    Payloads.full(request.prompt() != null ? request.prompt().getContents() : null));

            ChatClientResponse response = chain.nextCall(request);

            logAnswer(trace, response);
            return response;
        } catch (RuntimeException ex) {
            log.info("[{}] FAILED after {}s, {} tool call(s): {}", trace.id(),
                    format(trace.elapsedSeconds()), trace.toolCalls(), ex.toString());
            log.info(RULE);
            throw ex;
        } finally {
            TraceContext.end();
        }
    }

    private void logAnswer(ConversationTrace trace, ChatClientResponse response) {
        ChatResponse chatResponse = response != null ? response.chatResponse() : null;
        String answer = chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;

        log.info("[{}] ANSWER > {}", trace.id(), Payloads.oneLine(answer, properties));
        log.debug("[{}] full answer: {}", trace.id(), Payloads.full(answer));
        log.info("[{}] DONE in {}s | {} model round(s) | {} tool call(s){}", trace.id(),
                format(trace.elapsedSeconds()), trace.modelRounds(), trace.toolCalls(), tokens(chatResponse));
        log.info(RULE);
    }

    /** Token counts when the provider reports them; Ollama does, which makes context growth visible. */
    private String tokens(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return "";
        }
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null || usage.getPromptTokens() == null) {
            return "";
        }
        return " | tokens: prompt=%d completion=%s".formatted(
                usage.getPromptTokens(),
                usage.getCompletionTokens() != null ? usage.getCompletionTokens() : "?");
    }

    /** The question itself, which is the last user message on the prompt. */
    private String userText(ChatClientRequest request) {
        if (request.prompt() == null) {
            return "<no prompt>";
        }
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return "<no messages>";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.USER) {
                return Payloads.oneLine(message.getText(), properties);
            }
        }
        return Payloads.oneLine(messages.get(messages.size() - 1).getText(), properties);
    }

    private String format(double seconds) {
        return String.format("%.2f", seconds);
    }
}
