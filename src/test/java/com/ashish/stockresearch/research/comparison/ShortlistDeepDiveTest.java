package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.TestFilings;
import com.ashish.stockresearch.research.ValuationService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.provider.mock.MockStockResearchProvider;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// S5-4
class ShortlistDeepDiveTest {

    private static StockResearchReport report(String symbol) {
        StockResearchService research = new StockResearchService(
                new MockStockResearchProvider(), new MockStockResearchProvider(), new UnsupportedShareholdingProvider(),
                new MockStockResearchProvider(), new FinancialMetricsService(), new HistoricalPerformanceService(),
                new MockStockResearchProvider(), new ValuationService(BigDecimal.valueOf(5)), TestFilings.none());
        return new ResearchReportService(research, new FinancialDataValidator(Clock.systemUTC()), new SectorClassifier())
                .build(symbol);
    }

    @Test
    void fetchesEveryCompanysDataAtOnceThenWritesEachReportInTurn() throws InterruptedException {
        List<String> symbols = List.of("AAA", "BBB", "CCC");
        CountDownLatch concurrent = new CountDownLatch(symbols.size());
        ResearchReportService researchReportService = mock(ResearchReportService.class);
        for (String symbol : symbols) {
            when(researchReportService.build(symbol)).thenAnswer(invocation -> {
                concurrent.countDown();
                // none may return until every one of them has started - proof they run on virtual threads at once
                if (!concurrent.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("the companies' data was not all fetched at once");
                }
                return report(symbol);
            });
        }
        List<String> writeOrder = new CopyOnWriteArrayList<>();
        ResearchReportWriter researchReportWriter = mock(ResearchReportWriter.class);
        when(researchReportWriter.write(any(StockResearchReport.class))).thenAnswer(invocation -> {
            StockResearchReport built = invocation.getArgument(0);
            writeOrder.add(built.symbol());
            return "# report on " + built.symbol();
        });

        List<String> reports = new ShortlistDeepDive(researchReportService, researchReportWriter).run(symbols);

        assertThat(reports).containsExactly("# report on AAA", "# report on BBB", "# report on CCC");
        // the model is called for one company's interpretation after another, never in parallel
        assertThat(writeOrder).containsExactly("AAA", "BBB", "CCC");
    }
}
