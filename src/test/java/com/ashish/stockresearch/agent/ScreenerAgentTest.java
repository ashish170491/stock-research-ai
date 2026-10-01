package com.ashish.stockresearch.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.report.AnswerChecks;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ScreeningCountVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.CriteriaValidator;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.screening.SnapshotDatabase;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.screening.Universe;
import com.ashish.stockresearch.screening.Universes;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.trace.ChainTracer;
import com.ashish.stockresearch.trace.ConversationTraceAdvisor;
import com.ashish.stockresearch.trace.TraceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.ashish.stockresearch.screening.ScreeningFixtures.DATE;
import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROA_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_3Y_AVG_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screener chain end to end: a scripted model for stages 1 and 4, the real validator, screener and
 * checks, over an in-memory snapshot.
 */
class ScreenerAgentTest {

    private static final String REQUEST = "Find profitable IT companies with low debt and ROE above 18%";
    private static final String EXTRACTED = """
            {"preset":null,"industryGroups":["IT_SERVICES"],
             "criteria":[{"phrase":"ROE above 18%","metric":"ROE_PERCENT","operator":"ABOVE","threshold":"18"}],
             "vagueTerms":["profitable","low debt"]}""";
    private static final String GOOD_SUMMARY = "1 of the 3 screened stocks meet every criterion: TCS. 1 of the 3 have "
            + "at least one criterion that could not be assessed.";

    /** Replies to the extraction prompt and the summary prompt by script; records every prompt. */
    private static final class ScriptedModel implements ChatModel {

        private final Function<Prompt, String> extraction;
        private final Function<Prompt, String> summary;
        private final List<Prompt> prompts = new ArrayList<>();

        ScriptedModel(Function<Prompt, String> extraction, Function<Prompt, String> summary) {
            this.extraction = extraction;
            this.summary = summary;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").temperature(0.3).numCtx(16384).build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            String system = prompt.getInstructions().stream().filter(m -> m.getMessageType() == MessageType.SYSTEM)
                    .map(m -> m.getText()).findFirst().orElse("");
            String reply = system.contains("write down its criteria as JSON") ? extraction.apply(prompt)
                    : summary.apply(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply).build())));
        }

        String userText(int call) {
            return prompts.get(call).getInstructions().stream().filter(m -> m.getMessageType() == MessageType.USER)
                    .map(m -> m.getText()).reduce("", String::concat);
        }
    }

    private final EmbeddedDatabase database = SnapshotDatabase.create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private final ScreeningPresets presets = ScreeningPresetsTest.presets();
    private final TraceProperties trace = new TraceProperties(true, 2000);
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger traceLogger;

    @BeforeEach
    void setUp() {
        traceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.ashish.stockresearch.trace.Trace");
        traceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        traceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(appender);
        database.shutdown();
    }

    /** TCS meets every criterion; INFY's ROE fails; WIPRO's D/E is disputed; HDFCBANK is left out by the IT filter. */
    private void snapshot() {
        Instant now = Instant.parse("2026-09-28T13:00:00Z");
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, now, 4);
        repository.save(run.id(), stock("TCS", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "40")
                .with(ROE_3Y_AVG_PERCENT, "35").with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build());
        repository.save(run.id(), stock("INFY", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "16")
                .with(ROE_3Y_AVG_PERCENT, "20").with(DEBT_TO_EQUITY_MULTIPLE, "0.05").build());
        repository.save(run.id(), stock("WIPRO", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "19")
                .with(ROE_3Y_AVG_PERCENT, "17").with(withStatus(DEBT_TO_EQUITY_MULTIPLE, DataStatus.DATA_CONFLICT, "0.2"))
                .build());
        repository.save(run.id(), stock("HDFCBANK", IndustryGroup.BANK).with(ROA_PERCENT, "1.8")
                .with(ROE_PERCENT, "14").build());
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 4, 0, now.plusSeconds(60));
    }

    private ScreenerAgent agent(ChatModel model) {
        ScreeningService service = new ScreeningService(repository, new StockScreener(), presets, new Universes());
        return new ScreenerAgent(ChatClient.builder(model), new ConversationTraceAdvisor(trace), presets,
                new CriteriaValidator(presets, ScreeningPresetsTest.properties()), service, new ScreeningRenderer(),
                new AnswerChecks(new NumericClaimVerifier(), new ScreeningCountVerifier(), new UnsupportedClaimFilter()),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"), new ChainTracer(trace));
    }

    private List<String> traceLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // S4-2: stage 1 is structured output from a tool-free client with thinking off at temperature 0.
    @Test
    void extractsTheCriteriaAsStructuredOutputWithThinkingOffAtZeroTemperature() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);

        agent(model).answer(REQUEST);

        Prompt extraction = model.prompts.get(0);
        assertThat(extraction.getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
            assertThat(options.getTemperature()).isEqualTo(0.0);
            assertThat(options.getToolCallbacks()).isNullOrEmpty();
        });
        // the whitelist is offered, and the reply is asked for in ExtractedCriteria's JSON shape
        assertThat(extraction.getContents()).contains("ROE_PERCENT", "DEBT_TO_EQUITY_MULTIPLE", "IT_SERVICES")
                .contains("vagueTerms").contains(REQUEST);
    }

    // S4-4 / S4-5: every assumption shown; StockScreener's table; a summary from the result only, checked.
    @Test
    void showsHowTheRequestWasReadThenTheSummaryThenTheTable() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);

        ScreenerAgent.ScreenAnswer answer = agent(model).answer(REQUEST);

        assertThat(answer.text()).startsWith("**How I read your request**\n")
                .contains("- “IT” → only companies in the IT_SERVICES industry group\n")
                .contains("- “ROE above 18%” → ROE > 18.00% (return on equity, latest fiscal year)\n")
                .contains("- “low debt” → D/E ≤ 0.50x (debt-to-equity, latest fiscal year end): the quality-compounder "
                        + "preset's threshold, as no number was given\n")
                .contains("- “profitable” → ROE 3y avg ≥ 15.00%")
                .contains(GOOD_SUMMARY)
                .contains("| Rank | Symbol | Company | Group | Met |")
                .contains("- 1 of the 3 screened stocks meet every criterion that applies to them: TCS (IT_SERVICES).")
                .doesNotContain("TOOL RESULT").doesNotContain("Removed");
        assertThat(answer.text().indexOf(GOOD_SUMMARY)).isLessThan(answer.text().indexOf("| Rank |"));
        assertThat(answer.evidence()).startsWith("TOOL RESULT: screenStocks - status OK");
        assertThat(answer.memoryNote()).contains("1 of the 3 screened stocks meet every criterion: TCS");
    }

    @Test
    void theSummaryIsWrittenFromTheResultAloneByAToolFreeClientWithThinkingOff() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);

        agent(model).answer(REQUEST);

        assertThat(model.prompts).hasSize(2);
        assertThat(model.prompts.get(1).getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
            assertThat(options.getToolCallbacks()).isNullOrEmpty();
        });
        // INV-4: the summary writes about companies, so it carries the shared evidence rules
        assertThat(ScreenerAgent.SUMMARY_PROMPT).contains(com.ashish.stockresearch.service.ResearchInstructions.EVIDENCE_RULES);
        String shown = model.userText(1);
        assertThat(shown).contains("#### Summary (counted by the application)").contains("Criteria for Non-financial")
                // not the request, and not the table to tally
                .doesNotContain(REQUEST).doesNotContain("| Rank |");
    }

    @Test
    void theChecksRemoveAWrongCountAnInventedFigureAndAJudgementFromTheSummary() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY
                + " Only 12 stocks meet all criteria. TCS has an ROE of 41.25%. TCS is a quality company.");

        String text = agent(model).answer(REQUEST).text();
        String summary = text.substring(0, text.indexOf("_Removed"));

        assertThat(summary).contains(GOOD_SUMMARY).doesNotContain("Only 12 stocks").doesNotContain("41.25")
                .doesNotContain("quality company");
        // each removal is noted, naming what was removed
        assertThat(text).contains("_Removed 1 statement(s) containing figures that do not appear")
                .contains("_Removed 1 statement(s) with counts the screen does not state")
                .contains("_Removed 1 statement(s) making claims the data does not support");
    }

    @Test
    void showsWhatWasNotAppliedAndWhy() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> """
                {"preset":null,"industryGroups":["IT_SERVICES"],"criteria":[
                 {"phrase":"ROE above 18%","metric":"ROE_PERCENT","operator":"ABOVE","threshold":"18"},
                 {"phrase":"promoter holding above 50%","metric":"PROMOTER_HOLDING","operator":"ABOVE","threshold":"50"}],
                 "vagueTerms":["cheap"]}""", p -> GOOD_SUMMARY);

        String text = agent(model).answer("Find cheap IT companies with ROE above 18% and promoter holding above 50%")
                .text();

        assertThat(text).contains("**Not applied**\n")
                .contains("‘PROMOTER_HOLDING’ is not a metric the screen can test")
                .contains("“cheap” is a judgement, and this application does not judge stocks");
    }

    // S4-6: an extraction failure is never an exception, and never an unscreened answer.
    @Test
    void refusesClearlyWhenExtractionFailsOnARequestWithNumbers() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> "I think you want some IT stocks.", p -> {
            throw new AssertionError("no summary without a screen");
        });

        ScreenerAgent.ScreenAnswer answer = agent(model).answer(REQUEST);

        assertThat(answer.text()).contains("I could not read the criteria in your request, so I did not run a screen")
                .doesNotContain("| Rank |");
        assertThat(answer.evidence()).isEmpty();
        assertThat(model.prompts).hasSize(1);
    }

    @Test
    void screensFromTheRequestsOwnWordsWhenExtractionFailsOnARequestWithoutNumbers() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> {
            throw new ResourceAccessException("connection refused");
        }, p -> GOOD_SUMMARY);

        String text = agent(model).answer("Find profitable IT companies with low debt").text();

        assertThat(text).contains("- “low debt” → D/E ≤ 0.50x").contains("| Rank |");
    }

    @Test
    void showsTheTableWithoutASummaryWhenTheModelCannotWriteOne() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> {
            throw new ResourceAccessException("connection refused");
        });

        String text = agent(model).answer(REQUEST).text();

        assertThat(text).contains(ScreenerAgent.NO_SUMMARY).contains("| Rank |")
                .contains("- 1 of the 3 screened stocks meet every criterion");
    }

    @Test
    void saysSoWhenThereIsNoSnapshotToScreen() {
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);

        ScreenerAgent.ScreenAnswer answer = agent(model).answer(REQUEST);

        assertThat(answer.text()).contains("**How I read your request**").contains("No completed snapshot of Nifty 50");
        assertThat(model.prompts).hasSize(1);
    }

    // S4-7: every stage, and each model call within it, in one conversation trace.
    @Test
    void tracesEveryStageOfTheChainUnderOneId() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);

        agent(model).answer(REQUEST);

        List<String> lines = traceLines();
        String id = lines.stream().filter(l -> l.contains("USER > " + REQUEST)).findFirst().orElseThrow()
                .substring(1, 7);
        assertThat(lines).filteredOn(l -> l.startsWith("[" + id + "]")).extracting(l -> l.substring(9))
                .anyMatch(l -> l.startsWith("CHAIN screener: stage 1 extract criteria > ExtractedCriteria[preset=null"))
                .anyMatch(l -> l.startsWith("CHAIN screener: stage 2 validate > custom [ROE > 18.00%"))
                .anyMatch(l -> l.startsWith("CHAIN screener: stage 3 screen > OK: 3 screened, 1 meet every criterion"))
                .anyMatch(l -> l.startsWith("CHAIN screener: stage 4 summarise > 0 statement(s) removed"))
                .anyMatch(l -> l.startsWith("  model call 1 > "))
                .anyMatch(l -> l.startsWith("  model call 2 < in "))
                .anyMatch(l -> l.startsWith("DONE in ") && l.endsWith("| 2 model call(s)"));
        // one trace, not one per model call
        assertThat(lines).filteredOn(l -> l.contains("USER > ")).hasSize(1);
    }

    // Progress: the four stages are reported as they happen.
    @Test
    void reportsEachStageOfTheChainAsItHappens() {
        snapshot();
        ScriptedModel model = new ScriptedModel(p -> EXTRACTED, p -> GOOD_SUMMARY);
        List<com.ashish.stockresearch.trace.Progress.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (com.ashish.stockresearch.trace.Progress.Scope scope = com.ashish.stockresearch.trace.Progress.open(events::add)) {
            agent(model).answer(REQUEST);
        }

        assertThat(events).filteredOn(e -> e.status() != com.ashish.stockresearch.trace.Progress.Status.STARTED)
                .extracting(com.ashish.stockresearch.trace.Progress.Event::label).containsExactly(
                        "Read the criteria in your request",
                        "Checked the criteria against your words",
                        "Screened 3 stocks from the snapshot of " + DATE,
                        "Wrote the summary; every count checked");
    }
}
