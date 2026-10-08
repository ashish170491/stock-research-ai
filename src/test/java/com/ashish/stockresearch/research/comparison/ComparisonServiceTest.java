package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.TestFilings;
import com.ashish.stockresearch.research.ValuationService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.provider.mock.MockStockResearchProvider;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.service.OllamaCalls;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// S5-3, S5-4, S5-5
class ComparisonServiceTest {

    private static final class FixedModel implements ChatModel {

        private final String content;
        final List<Prompt> prompts = new ArrayList<>();

        FixedModel(String content) {
            this.content = content;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
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

    /** A real, independently-built report from the development mock provider - no network, no per-symbol fixtures. */
    private static StockResearchReport buildReport(String symbol) {
        StockResearchService research = new StockResearchService(
                new MockStockResearchProvider(), new MockStockResearchProvider(), new UnsupportedShareholdingProvider(),
                new MockStockResearchProvider(), new FinancialMetricsService(), new HistoricalPerformanceService(),
                new MockStockResearchProvider(), new ValuationService(BigDecimal.valueOf(5)), TestFilings.none());
        return new ResearchReportService(research, new FinancialDataValidator(Clock.systemUTC()), new SectorClassifier())
                .build(symbol);
    }

    private final ResearchReportService researchReportService = mock(ResearchReportService.class);
    private final RatioContextService contextService =
            new RatioContextService(mock(FundamentalsSnapshotRepository.class), 5);
    private final MetricGlossary glossary = TestGlossary.glossary();
    private final ComparisonBuilder builder = new ComparisonBuilder(glossary);

    private ComparisonService service(ChatModel model) {
        return new ComparisonService(ChatClient.builder(model), new PassThrough(), researchReportService, contextService,
                builder, new NumericClaimVerifier(), new UnsupportedClaimFilter(),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"));
    }

    @Test
    void fetchesEveryCompanyAtOnceAndWritesOneNarrativeOverTheAlignedTable() {
        when(researchReportService.build("AAA")).thenReturn(buildReport("AAA"));
        when(researchReportService.build("BBB")).thenReturn(buildReport("BBB"));
        FixedModel model = new FixedModel("Both companies show the figures given in the table.");

        String result = service(model).compare(List.of("AAA", "BBB"));

        assertThat(result).contains("## Comparison").contains("Both companies show the figures given in the table.");
        // one narrative call over the aligned table, not a separate model call per company (S5-3, S5-4)
        assertThat(model.prompts).hasSize(1);
    }

    @Test
    void oneCompanyFailingToLoadStillGivesAComparisonOfTheRestWithADataGap() {
        when(researchReportService.build("AAA")).thenReturn(buildReport("AAA"));
        when(researchReportService.build("BBB")).thenReturn(buildReport("BBB"));
        when(researchReportService.build("CCC")).thenThrow(new RuntimeException("no quote could be resolved for CCC"));
        FixedModel model = new FixedModel("The table compares the two companies that were built.");

        String result = service(model).compare(List.of("AAA", "BBB", "CCC"));

        assertThat(result).contains("## Comparison")
                .contains("CCC could not be compared: no quote could be resolved for CCC");
    }

    @Test
    void removesANarrativeSentenceWhoseFigureIsNotInTheTable() {
        when(researchReportService.build("AAA")).thenReturn(buildReport("AAA"));
        when(researchReportService.build("BBB")).thenReturn(buildReport("BBB"));
        FixedModel model = new FixedModel("AAA's return on equity is 999.99%, a figure not in the table.");

        String result = service(model).compare(List.of("AAA", "BBB"));

        assertThat(result).doesNotContain("999.99%")
                .contains("sentence(s) were removed from this narrative because their figures do not appear in the table");
    }
}
