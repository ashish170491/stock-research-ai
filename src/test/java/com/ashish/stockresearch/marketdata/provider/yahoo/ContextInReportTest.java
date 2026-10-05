package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.context.ContextFixtures;
import com.ashish.stockresearch.context.ContextRenderer;
import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.SnapshotDatabase;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context beside each ratio in a real report (roadmap Step 13): HDFC Bank's recorded Yahoo data through the
 * whole report pipeline, with six bank peers in a stored Nifty 500 snapshot and a model that writes a fixed
 * interpretation.
 */
class ContextInReportTest {

    /**
     * Restates a peer median with its date and count, a median the context does not give, and one sentence that
     * places nothing. None names return on equity: for this recorded data it is a withheld topic.
     */
    static final String INTERPRETATION = "The 6 BANK peers in the snapshot of 2026-10-02 have a median of 14.30%. "
            + "Their median is 15.55% in the snapshot of 2026-10-02 for 6 peers. The report shows the context of each "
            + "ratio beside it.";

    private static final class FixedModel implements ChatModel {

        private final String interpretation;
        private final List<Prompt> prompts = new ArrayList<>();

        FixedModel(String interpretation) {
            this.interpretation = interpretation;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(interpretation).build())));
        }
    }

    private static final class PassThrough implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public String getName() {
            return "pass-through";
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private final EmbeddedDatabase database = SnapshotDatabase.create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private final MetricGlossary glossary = TestGlossary.glossary();
    private final FixedModel model = new FixedModel(INTERPRETATION);

    @AfterEach
    void close() {
        database.shutdown();
    }

    private String report() {
        return report(model);
    }

    private String report(FixedModel scriptedModel) {
        SnapshotRun run = repository.startRun(Universe.NIFTY500, LocalDate.of(2026, 10, 2),
                Instant.parse("2026-10-02T02:57:44Z"), 12);
        ContextFixtures.snapshot().forEach(snapshot -> repository.save(run.id(), snapshot));
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 12, 0, Instant.parse("2026-10-02T04:08:25Z"));
        ResearchReportService service = new ResearchReportService(ResearchPipelineRegressionTest.research("HDFCBANK"),
                new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026), new SectorClassifier());
        return new ResearchReportWriter(ChatClient.builder(scriptedModel), new PassThrough(), service,
                new ResearchReportRenderer(), new UnsupportedClaimFilter(), new NumericClaimVerifier(),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"), glossary,
                new RatioContextService(repository, 5), false).write("HDFCBANK");
    }

    // S13-L1, offline: HDFC Bank's ROE beside its own years and its bank peers, with the snapshot date and count
    @Test
    void showsHdfcBanksRoeBesideItsOwnYearsAndItsBankPeers() {
        String report = report();

        int section = report.indexOf("## Context beside each ratio");
        assertThat(section).isGreaterThan(report.indexOf("## At a glance, by question"))
                .isLessThan(report.indexOf("## 1. FACTS"));
        String context = report.substring(section, report.indexOf("## 1. FACTS"));
        String roe = context.substring(context.indexOf("**Return on equity** (returnOnEquityPercent)"));
        roe = roe.substring(0, roe.indexOf("\n\n"));
        assertThat(roe).contains("- Own fiscal years (as the provider reports them): FY")
                .contains("- BANK peers in the Nifty 500 snapshot of 2026-10-02 (6 peers with a usable value): median "
                        + "14.30%, range 8.60% to 18.10%. HDFCBANK in that snapshot: 14.03% (FY26), the 4th highest of 7");
        // a bank's ROCE and debt-to-equity have no context
        assertThat(context).doesNotContain("(returnOnCapitalEmployedPercent)\n")
                .contains("not primary measures for BANK companies.");
    }

    // S13-6: the model is never given a peer figure to restate - every peer median, range and rank reaches the
    // reader only through the application's own rendering, so a model sentence that states one, however worded,
    // quotes a number its evidence does not have and is removed as ungrounded
    @Test
    void neverGivesTheModelAPeerFigureToRestate() {
        String report = report();
        String interpretation = report.substring(report.indexOf("## 4. INTERPRETATION"), report.indexOf("## 5. SOURCES"));
        String written = interpretation.substring(0, interpretation.indexOf("\n_2 sentence(s) were removed"));

        // the prompt the model actually received carries the company's own years but no peer median, range or rank
        assertThat(model.prompts).singleElement().satisfies(prompt -> assertThat(prompt.getContents())
                .contains("CONTEXT\n## Context beside each ratio")
                .contains("Peer figures (median, range, rank) for this ratio are shown in the report beside it, not "
                        + "in this evidence pack.")
                .contains("never state, estimate or imply how the company compares with other companies")
                .doesNotContain("median 14.30%").doesNotContain("BANK peers in the Nifty 500 snapshot"));
        // the one sentence that states nothing is kept; both peer-median sentences are gone from what is shown
        assertThat(written).contains("The report shows the context of each ratio beside it.")
                .doesNotContain("14.30%").doesNotContain("15.55%");
        // neither sentence's figure is in the model's evidence, so both are removed as ungrounded - the real
        // peer median (14.30, with its date and count) exactly as an invented one (15.55) would be
        assertThat(interpretation).contains("2 sentence(s) were removed from this interpretation because their "
                + "figures do not appear in the evidence (14.30, 15.55)");
    }

    // S13-6: every wording a model might use to restate the real peer median (14.30%, range 8.60% to 18.10%, from
    // ContextFixtures) is removed, with no exception for a bare figure that mentions no "peer"-like word
    @Test
    void removesEveryWordingThatRestatesARealPeerFigure() {
        String interpretation = "Nifty 500 lenders earn about 14% on equity. Nifty 500 lenders earn about 14.3% on "
                + "equity. HDFC Bank's return on equity sits in the middle of its bank peers. Its return on equity is "
                + "above what Nifty 500 lenders usually trade at. By return on equity it is second only to a handful "
                + "of lenders. HDFC Bank's return on equity places it 7th among 26 banks in the Nifty 500 snapshot of "
                + "2026-10-02. The report shows the context of each ratio beside it.";

        String report = report(new FixedModel(interpretation));

        String section = report.substring(report.indexOf("## 4. INTERPRETATION"), report.indexOf("## 5. SOURCES"));
        // what the reader is told was concluded: only the one sentence that restates nothing of its own. Every
        // removal note below it is a disclosure of what was taken out, by design, not a leftover leak.
        java.util.regex.Matcher firstNote = java.util.regex.Pattern.compile("\\n_\\d+ sentence\\(s\\) were removed")
                .matcher(section);
        String written = firstNote.find() ? section.substring(0, firstNote.start()) : section;
        assertThat(written).doesNotContain("14%").doesNotContain("14.3%").doesNotContain("middle of")
                .doesNotContain("Nifty 500 lenders").doesNotContain("handful of").doesNotContain("7th among");
        // the one sentence that states nothing of its own is the only one kept
        assertThat(written).contains("The report shows the context of each ratio beside it.");
        // every sentence was in fact removed, not silently merged away
        assertThat(section).contains("sentence(s) were removed");
    }

    // the widest universe with a completed snapshot gives the peers; with none, the context says so
    @Test
    void takesPeersFromTheWidestSnapshotAndSaysWhenThereIsNone() {
        ResearchReportService service = new ResearchReportService(ResearchPipelineRegressionTest.research("HDFCBANK"),
                new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026), new SectorClassifier());
        var report = service.build("HDFCBANK");
        RatioContextService contexts = new RatioContextService(repository, 5);

        var none = contexts.build(report, ResearchReportRenderer.disputed(report));
        assertThat(none.run()).isNull();
        assertThat(none.peerNote()).isEqualTo("No completed fundamentals snapshot exists yet, so no peer figures are given.");
        // the recorded Yahoo years are all disputed for this bank, so without peers no ratio has context
        assertThat(none.ratios()).allSatisfy(ratio -> assertThat(ratio.peers()).isEmpty());
        assertThat(ContextRenderer.render(none, null)).contains("- No ratio could be given context.")
                .contains("No completed fundamentals snapshot exists yet");

        SnapshotRun nifty50 = repository.startRun(Universe.NIFTY50, LocalDate.of(2026, 10, 2),
                Instant.parse("2026-10-02T13:00:00Z"), 12);
        ContextFixtures.snapshot().forEach(snapshot -> repository.save(nifty50.id(), snapshot));
        repository.finishRun(nifty50.id(), SnapshotRun.Status.COMPLETED, 12, 0, Instant.parse("2026-10-02T13:05:00Z"));
        assertThat(contexts.build(report, ResearchReportRenderer.disputed(report)).run().universe())
                .isEqualTo(Universe.NIFTY50);
        report();
        assertThat(contexts.build(report, ResearchReportRenderer.disputed(report)).run().universe())
                .isEqualTo(Universe.NIFTY500);
    }

    @Test
    void givesNoPeersInAGroupThatIsNotOneIndustry() {
        var report = new ResearchReportService(ResearchPipelineRegressionTest.research("TECHM"),
                new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026), new SectorClassifier())
                .build("TECHM");
        var other = new com.ashish.stockresearch.research.report.StockResearchReport(report.requestedSymbol(),
                report.symbol(), report.companyName(), report.generatedOn(), report.latestReportedQuarter(),
                com.ashish.stockresearch.research.sector.SectorContext.of(
                        com.ashish.stockresearch.research.sector.IndustryGroup.OTHER, "test"),
                report.companyProfile(), report.financials(), report.historicalPerformance(), report.shareholding(),
                report.valuation(), report.observations(), report.withheldObservations(), report.dataGaps(),
                report.dataQualityIssues(), report.sources());

        var context = new RatioContextService(repository, 5).build(other, point -> false);

        assertThat(context.run()).isNull();
        assertThat(context.peerNote()).startsWith("OTHER is not one industry");
        // its own years still have context
        assertThat(context.ratios()).anySatisfy(ratio -> assertThat(ratio.history()).isPresent());
    }
}
