package com.ashish.stockresearch.research.critic;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// S6-5
class BearCaseWriterTest {

    private static final class FixedModel implements ChatModel {

        private final String content;

        FixedModel(String content) {
            this.content = content;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
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

    private static final OllamaCalls OLLAMA = new OllamaCalls("qwen3:8b", "http://localhost:11434");

    /** A real report from the development mock provider: always has at least the shareholding data gap. */
    private static StockResearchReport report(String symbol) {
        StockResearchService research = new StockResearchService(
                new MockStockResearchProvider(), new MockStockResearchProvider(), new UnsupportedShareholdingProvider(),
                new MockStockResearchProvider(), new FinancialMetricsService(), new HistoricalPerformanceService(),
                new MockStockResearchProvider(), new ValuationService(BigDecimal.valueOf(5)), TestFilings.none());
        return new ResearchReportService(research, new FinancialDataValidator(Clock.systemUTC()), new SectorClassifier())
                .build(symbol);
    }

    private static BearCaseWriter writer(ChatModel model) {
        return new BearCaseWriter(ChatClient.builder(model), new PassThrough(), new NumericClaimVerifier(),
                new UnsupportedClaimFilter(), OLLAMA);
    }

    @Test
    void evidenceHasOnlyDataGapsAndQualityIssuesNeverFactsOrObservations() {
        StockResearchReport report = report("AAA");

        String evidence = BearCaseWriter.evidence(report);

        assertThat(evidence).contains("Shareholding");
        report.observations().forEach(o -> assertThat(evidence).doesNotContain(o.statement()));
    }

    @Test
    void writesFromTheRestrictedEvidenceAndStripsAFigureNotInIt() {
        StockResearchReport report = report("AAA");
        FixedModel model = new FixedModel("Shareholding data is unavailable, which limits confidence. Revenue "
                + "fell 999.99% last year, a figure this evidence does not give.");

        String bearCase = writer(model).write(report);

        assertThat(bearCase).contains("Shareholding data is unavailable")
                .doesNotContain("999.99%")
                .contains("statement(s) were removed from the bear case");
    }
}
