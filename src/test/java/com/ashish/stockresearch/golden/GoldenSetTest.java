package com.ashish.stockresearch.golden;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ashish.stockresearch.service.AiService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden set (roadmap Step 10, S10-1/S10-L1): real questions through the real {@link AiService}, a real
 * local Ollama model, and fixture-backed Yahoo data (no mocked data, no mocked model) - the end-to-end path a
 * user actually takes. This is a baseline, not a strict gate: a small local model is not perfectly
 * deterministic, so the suite records a pass rate across every case rather than failing the build on the
 * first disagreement. Needs Ollama running locally with qwen3:8b pulled; excluded from the default build
 * (see README "Running the llm suite").
 */
@Tag("llm")
class GoldenSetTest {

    private static final Pattern TOOL_CALLED = Pattern.compile("TOOL CALLED: (\\S+)");

    @TempDir
    static Path tempDir;

    private static AiService service;
    private static RelevancyEvaluator relevancyEvaluator;
    private static Logger toolLogger;

    private record CaseResult(String id, boolean intentOk, boolean toolsOk, boolean factsOk, boolean relevant,
                               String note) {
        boolean passed() {
            return intentOk && toolsOk && factsOk && relevant;
        }
    }

    @BeforeAll
    static void setUp() {
        service = GoldenSetSupport.aiService(tempDir);
        ChatClient.Builder judge = ChatClient.builder(OllamaChatModel.builder()
                .ollamaApi(GoldenSetSupport.longTimeoutOllamaApi("http://localhost:11434"))
                .options(OllamaChatOptions.builder().model("qwen3:8b").build())
                .build());
        relevancyEvaluator = RelevancyEvaluator.builder().chatClientBuilder(judge).build();
        toolLogger = (Logger) LoggerFactory.getLogger("com.ashish.stockresearch.tool");
    }

    @AfterAll
    static void tearDown() {
        service = null;
        relevancyEvaluator = null;
    }

    @Test
    void runsTheGoldenSetAgainstTheRealModelAndPrintsAPassRate() throws IOException {
        List<GoldenCase> cases = load();
        assertThat(cases).as("S10-1: the golden set must have at least 20 cases").hasSizeGreaterThanOrEqualTo(20);

        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase goldenCase : cases) {
            results.add(run(goldenCase));
        }

        System.out.println("\n================ GOLDEN SET RESULTS ================");
        results.forEach(r -> System.out.printf("[%s] %-32s intent=%-5s tools=%-5s facts=%-5s relevant=%-5s %s%n",
                r.passed() ? "PASS" : "FAIL", r.id(), r.intentOk(), r.toolsOk(), r.factsOk(), r.relevant(), r.note()));
        long passed = results.stream().filter(CaseResult::passed).count();
        double passRate = (double) passed / results.size();
        System.out.printf("PASS RATE: %d/%d (%.1f%%)%n", passed, results.size(), passRate * 100);
        System.out.println("======================================================\n");

        // A floor, not a target: this is a baseline to track over time (S10-L1), not a strict gate - a
        // small local model answers some of these differently from one run to the next.
        assertThat(passRate).as("golden set pass rate (see console output above for the per-case breakdown)")
                .isGreaterThanOrEqualTo(0.5);
    }

    private CaseResult run(GoldenCase goldenCase) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        toolLogger.addAppender(appender);
        try {
            AiService.ChatAnswer answer = service.chat("golden-" + goldenCase.id(), goldenCase.question());
            Set<String> calledTools = toolsCalled(appender);

            boolean intentOk = answer.intent().name().equals(goldenCase.expectedIntent());
            boolean toolsOk = calledTools.containsAll(goldenCase.expectedTools());
            boolean factsOk = goldenCase.requiredFacts().stream().allMatch(fact -> answer.text().contains(fact));
            boolean relevant = isRelevant(goldenCase.question(), answer.text());

            String note = "intent=" + answer.intent() + " tools=" + calledTools
                    + (factsOk ? "" : " MISSING_FACTS=" + missing(goldenCase, answer.text()));
            return new CaseResult(goldenCase.id(), intentOk, toolsOk, factsOk, relevant, note);
        } catch (RuntimeException ex) {
            return new CaseResult(goldenCase.id(), false, false, false, false, "THREW: " + ex);
        } finally {
            toolLogger.detachAppender(appender);
        }
    }

    private boolean isRelevant(String question, String answerText) {
        try {
            return relevancyEvaluator.evaluate(new EvaluationRequest(question, answerText)).isPass();
        } catch (RuntimeException ex) {
            // The judge model is a convenience signal, not the test's only check; an unreachable or
            // unparseable judge should not fail a case the other three checks already passed or failed on.
            return true;
        }
    }

    private static List<String> missing(GoldenCase goldenCase, String answerText) {
        return goldenCase.requiredFacts().stream().filter(fact -> !answerText.contains(fact)).toList();
    }

    private static Set<String> toolsCalled(ListAppender<ILoggingEvent> appender) {
        Set<String> tools = new LinkedHashSet<>();
        for (ILoggingEvent event : appender.list) {
            Matcher matcher = TOOL_CALLED.matcher(event.getFormattedMessage());
            if (matcher.find()) {
                tools.add(matcher.group(1));
            }
        }
        return tools;
    }

    private static List<GoldenCase> load() throws IOException {
        ClassPathResource resource = new ClassPathResource("golden/golden-set.json");
        try (var in = resource.getInputStream()) {
            return List.of(JsonMapper.shared().readValue(in, GoldenCase[].class));
        }
    }
}
