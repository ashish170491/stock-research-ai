package com.ashish.stockresearch.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallTracingManagerTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger traceLogger;
    private ToolCallingManager delegate;

    @BeforeEach
    void setUp() {
        traceLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("com.ashish.stockresearch.trace.Trace");
        traceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        traceLogger.addAppender(appender);
        delegate = mock(ToolCallingManager.class);
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(appender);
    }

    private ToolCallTracingManager tracing(boolean enabled) {
        return new ToolCallTracingManager(delegate, new TraceProperties(enabled, 600));
    }

    private ChatResponse responseRequesting(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }

    /** Real result object, not a mock - stubbing one inside another when(...) breaks Mockito. */
    private ToolExecutionResult resultWith(ToolResponseMessage.ToolResponse... responses) {
        return ToolExecutionResult.builder()
                .conversationHistory(List.<Message>of(
                        ToolResponseMessage.builder().responses(List.of(responses)).build()))
                .build();
    }

    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void logsTheToolsTheModelAskedForWithTheirArguments() {
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c1", "function", "getCompanyProfile", "{\"symbol\":\"TCS\"}"));
        when(delegate.executeToolCalls(any(), any())).thenReturn(
                resultWith(new ToolResponseMessage.ToolResponse("c1", "getCompanyProfile", "{\"success\":true}")));

        tracing(true).executeToolCalls(new Prompt("q"), response);

        assertThat(loggedMessages())
                .anyMatch(line -> line.contains("requested 1 tool call(s)"))
                .anyMatch(line -> line.contains("getCompanyProfile") && line.contains("\"symbol\":\"TCS\""));
    }

    @Test
    void logsThePayloadHandedBackToTheModel() {
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c1", "function", "getStockQuote", "{\"symbol\":\"TCS\"}"));
        when(delegate.executeToolCalls(any(), any())).thenReturn(resultWith(
                new ToolResponseMessage.ToolResponse("c1", "getStockQuote", "{\"price\":2082.0}")));

        tracing(true).executeToolCalls(new Prompt("q"), response);

        assertThat(loggedMessages()).anyMatch(line -> line.contains("<--") && line.contains("\"price\":2082.0"));
    }

    @Test
    void logsOnlyThisRoundsResultsWhenHistoryHasAccumulated() {
        // conversationHistory() carries every earlier round too; reprinting
        // those would make a second round look like it re-ran the first.
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c2", "function", "getFinancialSummary", "{\"symbol\":\"TCS\"}"));
        ToolExecutionResult result = resultWith(
                new ToolResponseMessage.ToolResponse("c1", "getCompanyProfile", "ROUND-ONE-PAYLOAD"),
                new ToolResponseMessage.ToolResponse("c2", "getFinancialSummary", "ROUND-TWO-PAYLOAD"));
        when(delegate.executeToolCalls(any(), any())).thenReturn(result);

        tracing(true).executeToolCalls(new Prompt("q"), response);

        assertThat(loggedMessages())
                .anyMatch(line -> line.contains("ROUND-TWO-PAYLOAD"))
                .noneMatch(line -> line.contains("ROUND-ONE-PAYLOAD"));
    }

    @Test
    void truncatesLongPayloadsSoTheFlowStaysReadable() {
        String huge = "x".repeat(5000);
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c1", "function", "getFinancialSummary", "{}"));
        when(delegate.executeToolCalls(any(), any())).thenReturn(
                resultWith(new ToolResponseMessage.ToolResponse("c1", "getFinancialSummary", huge)));

        tracing(true).executeToolCalls(new Prompt("q"), response);

        assertThat(loggedMessages())
                .anyMatch(line -> line.contains("[5000 chars]") && line.contains("truncated"));
        assertThat(loggedMessages()).allSatisfy(line -> assertThat(line.length()).isLessThan(1200));
    }

    @Test
    void staysSilentAndStillDelegatesWhenTracingIsDisabled() {
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c1", "function", "getStockQuote", "{}"));
        ToolExecutionResult expected = resultWith(
                new ToolResponseMessage.ToolResponse("c1", "getStockQuote", "{}"));
        when(delegate.executeToolCalls(any(), any())).thenReturn(expected);

        ToolExecutionResult actual = tracing(false).executeToolCalls(new Prompt("q"), response);

        assertThat(actual).isSameAs(expected);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void neverSwallowsAFailureFromTheDelegate() {
        ChatResponse response = responseRequesting(
                new AssistantMessage.ToolCall("c1", "function", "getStockQuote", "{}"));
        when(delegate.executeToolCalls(any(), any())).thenThrow(new IllegalStateException("boom"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tracing(true).executeToolCalls(new Prompt("q"), response))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handlesAResponseCarryingNoToolCallsWithoutFailing() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("just text"))));
        when(delegate.executeToolCalls(any(), any())).thenReturn(resultWith());

        tracing(true).executeToolCalls(new Prompt("q"), response);

        assertThat(loggedMessages()).anyMatch(line -> line.contains("requested 0 tool call(s)"));
    }

    @Test
    void doesNotLogToolDefinitionsAtInfoLevel() {
        tracing(true).resolveToolDefinitions(mock(org.springframework.ai.model.tool.ToolCallingChatOptions.class));

        verify(delegate).resolveToolDefinitions(any());
        assertThat(appender.list).isEmpty();
        verify(delegate, never()).executeToolCalls(any(), any());
    }
}
