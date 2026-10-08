package com.ashish.stockresearch.orchestrator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ashish.stockresearch.agent.ResearchSession;
import com.ashish.stockresearch.agent.ScreenerAgent;
import com.ashish.stockresearch.research.comparison.ComparisonService;
import com.ashish.stockresearch.research.comparison.ShortlistDeepDive;
import com.ashish.stockresearch.research.critic.Critique;
import com.ashish.stockresearch.research.critic.ResearchCritic;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.trace.ChainTracer;
import com.ashish.stockresearch.trace.ConversationTraceAdvisor;
import com.ashish.stockresearch.trace.TraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The autonomous research orchestrator (roadmap Step 8): Java runs every plan step (S8-2), a SCREEN
 * step always pauses for a checkpoint (S8-4), a budget stops a plan early with a partial memo naming
 * which limit (S8-3), and every step is logged to the trace with its duration and model-call count
 * (S8-7). {@link ScreenerAgent}, {@link ShortlistDeepDive}, {@link ComparisonService},
 * {@link DocumentSearchTools}, {@link ResearchCritic} and {@link MemoWriter} are mocked: each has its
 * own test suite for its own behaviour; this suite tests only the orchestrator's own sequencing.
 */
class ResearchOrchestratorTest {

    private static final OrchestratorProperties GENEROUS = new OrchestratorProperties(12, 20, Duration.ofMinutes(10));

    private static final class ScriptedModel implements ChatModel {

        private final String reply;
        final List<Prompt> prompts = new ArrayList<>();

        ScriptedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply).build())));
        }
    }

    private final TraceProperties traceProperties = new TraceProperties(true, 600);
    private final OllamaCalls ollama = new OllamaCalls("qwen3:8b", "http://localhost:11434");
    private final ChainTracer tracer = new ChainTracer(traceProperties);
    private final ScreenerAgent screenerAgent = mock(ScreenerAgent.class);
    private final ShortlistDeepDive deepDive = mock(ShortlistDeepDive.class);
    private final ComparisonService comparisonService = mock(ComparisonService.class);
    private final DocumentSearchTools documentSearch = mock(DocumentSearchTools.class);
    private final ResearchCritic critic = mock(ResearchCritic.class);
    private final MemoWriter memoWriter = mock(MemoWriter.class);

    private ResearchOrchestrator orchestrator(String plannerReply, OrchestratorProperties properties) {
        return orchestrator(new ScriptedModel(plannerReply), properties);
    }

    private ResearchOrchestrator orchestrator(ScriptedModel model, OrchestratorProperties properties) {
        return new ResearchOrchestrator(new PlannerChatClientBuilder(ChatClient.builder(model)),
                new ConversationTraceAdvisor(traceProperties),
                ollama, tracer, screenerAgent, deepDive, comparisonService, documentSearch, critic, memoWriter,
                properties);
    }

    // S8-1
    @Test
    void anUnbuildablePlanIsRejectedBeforeAnythingRuns() {
        ResearchOrchestrator orchestrator = orchestrator("not JSON at all", GENEROUS);

        String answer = orchestrator.start("compare TCS and INFY", new ResearchSession());

        assertThat(answer).contains("Could not build a usable research plan");
        verify(comparisonService, never()).compare(anyList());
        verify(memoWriter, never()).write(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // S8-1: a step type outside the fixed six is rejected before anything runs.
    @Test
    void anUnknownStepTypeIsRejectedBeforeAnythingRuns() {
        ResearchOrchestrator orchestrator = orchestrator(
                "{\"goal\":\"g\",\"steps\":[{\"type\":\"RECOMMEND\",\"description\":\"x\",\"symbols\":[]}]}", GENEROUS);

        String answer = orchestrator.start("g", new ResearchSession());

        assertThat(answer).contains("Could not build a usable research plan");
        verify(comparisonService, never()).compare(anyList());
    }

    // S8-2: the planner lays out steps only - it is never given tools to call a provider itself.
    @Test
    void thePlannerIsGivenNoTools() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"g\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\",\"symbols\":[\"TCS\",\"INFY\"]},"
                        + "{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(comparisonService.compare(List.of("TCS", "INFY"))).thenReturn("COMPARISON");
        when(memoWriter.write(eq("g"), org.mockito.ArgumentMatchers.anyString())).thenReturn("MEMO");

        orchestrator(model, GENEROUS).start("g", new ResearchSession());

        assertThat(model.prompts).singleElement().satisfies(prompt -> {
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
                assertThat(toolOptions.getToolCallbacks()).isNullOrEmpty();
            }
        });
    }

    // S8-2 / S8-5: Java runs every step and writes the memo from what they produced, with no recommendation
    // ever asked for or given.
    @Test
    void runsAPlanWithNoScreenStraightToTheMemo() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"compare TCS and INFY\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\","
                        + "\"symbols\":[\"TCS\",\"INFY\"]},{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(comparisonService.compare(List.of("TCS", "INFY"))).thenReturn("COMPARISON TEXT");
        when(memoWriter.write(eq("compare TCS and INFY"), contains("COMPARISON TEXT"))).thenReturn("THE MEMO");

        String answer = orchestrator(model, GENEROUS).start("compare TCS and INFY", new ResearchSession());

        assertThat(answer).isEqualTo("THE MEMO");
        verify(screenerAgent, never()).answer(org.mockito.ArgumentMatchers.anyString());
    }

    // S8-4
    @Test
    void pausesAfterScreenAndShowsTheShortlistWithoutRunningLaterSteps() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"find candidates\",\"steps\":[{\"type\":\"SCREEN\",\"description\":\"find candidates\","
                        + "\"symbols\":[]},{\"type\":\"DEEP_DIVE\",\"description\":\"\",\"symbols\":[]},"
                        + "{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(screenerAgent.answer("find candidates")).thenReturn(new ScreenerAgent.ScreenAnswer("SCREEN RESULT TEXT",
                "evidence", "note", null, List.of("TCS", "INFY")));
        ResearchSession session = new ResearchSession();

        String answer = orchestrator(model, GENEROUS).start("find candidates", session);

        assertThat(answer).contains("SCREEN RESULT TEXT").contains("TCS, INFY").contains("continue");
        assertThat(session.checkpoint()).isPresent();
        assertThat(session.checkpoint().get().nextStepIndex()).isEqualTo(1);
        assertThat(session.checkpoint().get().shortlist()).containsExactly("TCS", "INFY");
        verify(deepDive, never()).run(anyList());
        verify(memoWriter, never()).write(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // S8-4
    @Test
    void resumeRunsTheRemainingStepsUsingTheSavedShortlist() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"find candidates\",\"steps\":[{\"type\":\"SCREEN\",\"description\":\"find candidates\","
                        + "\"symbols\":[]},{\"type\":\"DEEP_DIVE\",\"description\":\"\",\"symbols\":[]},"
                        + "{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(screenerAgent.answer("find candidates")).thenReturn(new ScreenerAgent.ScreenAnswer("SCREEN RESULT",
                "evidence", "note", null, List.of("TCS", "INFY")));
        when(deepDive.run(List.of("TCS", "INFY"))).thenReturn(List.of("REPORT ON TCS", "REPORT ON INFY"));
        when(memoWriter.write(eq("find candidates"), contains("REPORT ON TCS"))).thenReturn("FINAL MEMO");
        ResearchSession session = new ResearchSession();
        orchestrator(model, GENEROUS).start("find candidates", session);

        String answer = orchestrator(model, GENEROUS).resume(session);

        assertThat(answer).isEqualTo("FINAL MEMO");
        assertThat(session.checkpoint()).isEmpty();
        verify(deepDive).run(List.of("TCS", "INFY"));
    }

    @Test
    void resumingWithNoCheckpointSaysSo() {
        String answer = orchestrator("irrelevant", GENEROUS).resume(new ResearchSession());

        assertThat(answer).contains("no paused research plan");
    }

    // S8-3
    @Test
    void stopsAtTheStepLimitAfterResumingAndReturnsAPartialMemoNamingIt() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"find candidates\",\"steps\":[{\"type\":\"SCREEN\",\"description\":\"find candidates\","
                        + "\"symbols\":[]},{\"type\":\"DEEP_DIVE\",\"description\":\"\",\"symbols\":[]},"
                        + "{\"type\":\"COMPARE\",\"description\":\"\",\"symbols\":[\"TCS\",\"INFY\"]},"
                        + "{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(screenerAgent.answer("find candidates")).thenReturn(new ScreenerAgent.ScreenAnswer("SCREEN RESULT",
                "evidence", "note", null, List.of("TCS", "INFY")));
        when(deepDive.run(List.of("TCS", "INFY"))).thenReturn(List.of("REPORT"));
        when(memoWriter.write(eq("find candidates"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("PARTIAL MEMO");
        OrchestratorProperties tightSteps = new OrchestratorProperties(2, 20, Duration.ofMinutes(10));
        ResearchSession session = new ResearchSession();
        orchestrator(model, tightSteps).start("find candidates", session);

        String answer = orchestrator(model, tightSteps).resume(session);

        assertThat(answer).contains("stopped early").contains("step limit").contains("PARTIAL MEMO");
        verify(comparisonService, never()).compare(anyList());
    }

    // S8-3
    @Test
    void stopsImmediatelyAtTheModelCallLimitAndReturnsAPartialMemo() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"compare\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\","
                        + "\"symbols\":[\"TCS\",\"INFY\"]},{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(memoWriter.write(eq("compare"), org.mockito.ArgumentMatchers.anyString())).thenReturn("PARTIAL MEMO");
        // The planner's own call already consumes the only model call this budget allows.
        OrchestratorProperties tightCalls = new OrchestratorProperties(12, 1, Duration.ofMinutes(10));

        String answer = orchestrator(model, tightCalls).start("compare", new ResearchSession());

        assertThat(answer).contains("stopped early").contains("model-call limit").contains("PARTIAL MEMO");
        verify(comparisonService, never()).compare(anyList());
    }

    // S8-3
    @Test
    void stopsImmediatelyAtTheWallClockLimitAndReturnsAPartialMemo() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"compare\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\","
                        + "\"symbols\":[\"TCS\",\"INFY\"]},{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(memoWriter.write(eq("compare"), org.mockito.ArgumentMatchers.anyString())).thenReturn("PARTIAL MEMO");
        OrchestratorProperties tightClock = new OrchestratorProperties(12, 20, Duration.ofNanos(1));

        String answer = orchestrator(model, tightClock).start("compare", new ResearchSession());

        assertThat(answer).contains("stopped early").contains("wall-clock limit").contains("PARTIAL MEMO");
        verify(comparisonService, never()).compare(anyList());
    }

    // S8-5: CRITIQUE's finding flows into the memo's evidence, so RISKS / DATA GAPS can draw on it.
    @Test
    void aCritiqueStepsFindingsBecomeEvidenceForTheMemo() {
        ScriptedModel model = new ScriptedModel(
                "{\"goal\":\"g\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\",\"symbols\":[\"TCS\",\"INFY\"]},"
                        + "{\"type\":\"CRITIQUE\",\"description\":\"\",\"symbols\":[]},"
                        + "{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
        when(comparisonService.compare(List.of("TCS", "INFY"))).thenReturn("COMPARISON TEXT");
        when(critic.review(contains("COMPARISON TEXT"), eq("COMPARISON TEXT")))
                .thenReturn(new Critique(List.of("an unsupported claim"), List.of(), List.of(), Critique.Verdict.REVISE));
        when(memoWriter.write(eq("g"), contains("an unsupported claim"))).thenReturn("MEMO WITH THE FINDING");

        String answer = orchestrator(model, GENEROUS).start("g", new ResearchSession());

        assertThat(answer).isEqualTo("MEMO WITH THE FINDING");
    }

    // S8-7
    @org.junit.jupiter.api.Nested
    class TraceLogging {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger traceLogger;

        @BeforeEach
        void captureLogs() {
            traceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.ashish.stockresearch.trace.Trace");
            traceLogger.setLevel(Level.INFO);
            appender = new ListAppender<>();
            appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
            appender.start();
            traceLogger.addAppender(appender);
        }

        @AfterEach
        void stopCapturing() {
            traceLogger.detachAppender(appender);
        }

        @Test
        void everyStepIsLoggedWithItsDurationAndModelCallCount() {
            ScriptedModel model = new ScriptedModel(
                    "{\"goal\":\"g\",\"steps\":[{\"type\":\"COMPARE\",\"description\":\"\","
                            + "\"symbols\":[\"TCS\",\"INFY\"]},{\"type\":\"WRITE_MEMO\",\"description\":\"\",\"symbols\":[]}]}");
            when(comparisonService.compare(List.of("TCS", "INFY"))).thenReturn("COMPARISON TEXT");
            when(memoWriter.write(eq("g"), org.mockito.ArgumentMatchers.anyString())).thenReturn("MEMO");

            orchestrator(model, GENEROUS).start("g", new ResearchSession());

            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).anyMatch(line -> line.contains("CHAIN orchestrator: stage 1 plan"));
            assertThat(lines).anyMatch(line -> line.contains("stage 2 COMPARE") && line.contains("ms")
                    && line.contains("model call(s)"));
            assertThat(lines).anyMatch(line -> line.contains("stage 3 WRITE_MEMO") && line.contains("ms")
                    && line.contains("model call(s)"));
        }
    }
}
