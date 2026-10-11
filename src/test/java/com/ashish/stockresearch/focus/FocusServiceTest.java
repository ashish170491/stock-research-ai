package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.service.ResearchReportWriter;
import com.ashish.stockresearch.verdict.VerdictEngine;
import com.ashish.stockresearch.verdict.VerdictProperties;
import com.ashish.stockresearch.verdict.VerdictRenderer;
import com.ashish.stockresearch.verdict.VerdictService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningMetric.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Only the chosen topics are answered, in Java, from the same verdict as the rating card. */
class FocusServiceTest {

    private final VerdictService verdicts = mock(VerdictService.class);
    private final StockResearchService research = mock(StockResearchService.class);
    private final ResearchReportWriter reportWriter = mock(ResearchReportWriter.class);
    private final FocusService focus = new FocusService(verdicts, new VerdictRenderer(TestGlossary.glossary()), research,
            reportWriter, new NseSymbolDirectory());

    private void ratedInfosys() {
        VerdictEngine engine = new VerdictEngine(ScreeningPresetsTest.presets(), VerdictProperties.defaults());
        when(verdicts.lookup("INFY")).thenReturn(new VerdictService.Lookup(engine.assess(stock("INFY", IndustryGroup.IT_SERVICES)
                .with(ROE_3Y_AVG_PERCENT, "30").with(ROCE_PERCENT, "38").with(REVENUE_CAGR_5Y_PERCENT, "12")
                .with(NET_PROFIT_CAGR_5Y_PERCENT, "9").with(OCF_TO_PAT_3Y_MULTIPLE, "1.1")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0").with(EARNINGS_YIELD_PERCENT, "7").build()), null));
    }

    @Test
    void answersOnlyTheChosenTopicsWithNoModelCall() {
        ratedInfosys();

        String md = focus.answer("Infosys", List.of(Topic.GROWING, Topic.STRETCHED));

        assertThat(md).startsWith("# INFY Limited (INFY)").contains("### Is it growing?")
                .contains("### Is it financially stretched?")
                .doesNotContain("### Is it profitable?").doesNotContain("Rating:").doesNotContain("share price done");
        verify(reportWriter, never()).write(anyString());
    }

    @Test
    void thePriceHistoryNeedsNoRatingAndSaysPlainlyWhenItIsMissing() {
        when(research.getHistoricalPerformance("INFY", 5)).thenReturn(HistoricalPerformanceResult.failure(
                ResearchStatus.PROVIDER_UNAVAILABLE, "Yahoo returned 429"));

        String md = focus.answer("Infosys", List.of(Topic.PERFORMANCE));

        assertThat(md).contains("### How has the share price done?")
                .contains("The share-price history could not be retrieved right now.").doesNotContain("429");
        verify(verdicts, never()).lookup(anyString());
    }

    @Test
    void theFullReportAloneIsTheReport() {
        when(reportWriter.write("INFY")).thenReturn("# Research report: Infosys");

        assertThat(focus.answer("Infosys", List.of(Topic.FULL_REPORT))).isEqualTo("# Research report: Infosys");
    }

    @Test
    void whenTheFiguresCannotBeRetrievedItSaysSoInPlainWords() {
        when(verdicts.lookup("INFY")).thenReturn(new VerdictService.Lookup(null,
                "I could not retrieve Infosys's figures right now. Please try again in a few minutes."));

        assertThat(focus.answer("Infosys", List.of(Topic.RATING)))
                .contains("I could not retrieve Infosys's figures right now.").doesNotContain("Rating:");
    }
}
