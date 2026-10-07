package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The glossary in a real report (roadmap Step 11): HDFC Bank's and Tech Mahindra's recorded Yahoo data
 * through the whole report pipeline, with a model that writes a fixed interpretation.
 */
class GlossaryInReportTest {

    /** Plain amounts, prices and counts the report shows, which need no explaining as a measure. */
    private static final Set<String> PLAIN_FIGURES = Set.of("totalDebt", "totalCash", "sharePrice", "startPrice",
            "endPrice", "periodHigh", "periodLow", "sharesOutstanding", "actualYears", "marketCap", "employees");

    private static final class FixedModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("The evidence covers the fiscal years shown. Some figures could not be cross-checked.")
                    .build())));
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

    private final MetricGlossary glossary = TestGlossary.glossary();
    /** No snapshot stored: the context gives each ratio its own years only. */
    private static final com.ashish.stockresearch.context.RatioContextService NO_SNAPSHOT =
            new com.ashish.stockresearch.context.RatioContextService(
                    new com.ashish.stockresearch.screening.FundamentalsSnapshotRepository(
                            com.ashish.stockresearch.screening.SnapshotDatabase.create()), 5);
    private final FixedModel model = new FixedModel();

    private String report(String symbol) {
        ResearchReportService service = new ResearchReportService(ResearchPipelineRegressionTest.research(symbol),
                new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026), new SectorClassifier());
        return new ResearchReportWriter(ChatClient.builder(model), new PassThrough(), service,
                new ResearchReportRenderer(), new UnsupportedClaimFilter(), new NumericClaimVerifier(),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"), glossary, NO_SNAPSHOT,
                com.ashish.stockresearch.research.critic.CriticTestSupport.alwaysAccept(),
                com.ashish.stockresearch.research.critic.CriticTestSupport.noBearCase(), false).write(symbol);
    }

    // S11-1: every metric the report shows is explained, apart from plain amounts and prices.
    @ParameterizedTest
    @ValueSource(strings = {"HDFCBANK", "TECHM"})
    void explainsEveryMetricTheReportShows(String symbol) {
        String report = report(symbol);

        Matcher rows = Pattern.compile("(?m)^\\| ([a-z][A-Za-z0-9]+) \\|").matcher(report);
        Set<String> metrics = new LinkedHashSet<>();
        while (rows.find()) {
            metrics.add(rows.group(1));
        }
        assertThat(metrics).isNotEmpty();
        assertThat(metrics).filteredOn(metric -> !PLAIN_FIGURES.contains(metric))
                .allSatisfy(metric -> assertThat(glossary.forMetricKey(metric)).as(metric).isPresent());
    }

    // S11-6: the report ends with the terms it uses, rendered by Java and never shown to the model.
    @ParameterizedTest
    @ValueSource(strings = {"HDFCBANK", "TECHM"})
    void endsWithTheTermsItUsesAndNeverShowsThemToTheModel(String symbol) {
        String report = report(symbol);

        int section = report.indexOf("## Explain the terms");
        assertThat(section).isGreaterThan(report.indexOf("## 4. INTERPRETATION"))
                .isGreaterThan(report.indexOf("## Appendix - calculation audit"));
        String body = report.substring(0, section);
        String terms = report.substring(section);
        List<GlossaryEntry> used = glossary.termsIn(body);
        assertThat(terms).isEqualTo(glossary.section(used));
        // only the terms the report uses
        assertThat(glossary.entries()).filteredOn(entry -> !used.contains(entry))
                .allSatisfy(entry -> assertThat(terms).doesNotContain("**" + entry.name() + "**"));
        assertThat(model.prompts).singleElement().satisfies(prompt -> assertThat(prompt.getContents())
                .doesNotContain("Explain the terms").doesNotContain("At a glance, by question")
                .doesNotContain(glossary.entries().get(0).meaning()));
    }

    // S11-5: the report's latest figures, grouped by question, ahead of the facts.
    @ParameterizedTest
    @ValueSource(strings = {"HDFCBANK", "TECHM"})
    void groupsTheLatestFiguresByQuestion(String symbol) {
        String report = report(symbol);

        int glance = report.indexOf("## At a glance, by question");
        assertThat(glance).isPositive().isLessThan(report.indexOf("## 1. FACTS"));
        String table = report.substring(glance, report.indexOf("## 1. FACTS"));
        assertThat(table.indexOf("| Is it profitable? |")).isPositive()
                .isLessThan(table.indexOf("| Is it growing? |"));
        assertThat(table.indexOf("| Is it growing? |"))
                .isLessThan(table.indexOf("| How much does the market charge for it? |"));
        assertThat(table).contains("| Return on equity (returnOnEquityPercent) |");
        // in glossary order within a question
        assertThat(table.indexOf("(returnOnEquityPercent)")).isLessThan(table.indexOf("(operatingMarginPercent)"));
    }

    // A bank's debt-to-equity is shown, but marked as not a measure of a bank.
    @ParameterizedTest
    @ValueSource(strings = {"HDFCBANK"})
    void marksAFigureThatIsNotAPrimaryMeasureForTheIndustry(String symbol) {
        String report = report(symbol);
        String table = report.substring(report.indexOf("## At a glance, by question"), report.indexOf("## 1. FACTS"));

        assertThat(table.lines().filter(line -> line.contains("(debtToEquityMultiple)")))
                .singleElement().asString().contains("(not a primary measure for BANK)");
        // a figure that is not usable shows its status, not the reason FACTS gives
        assertThat(table).doesNotContain("UNAVAILABLE - ");
    }
}
