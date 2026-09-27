package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.ResearchFixtures;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.provider.mock.MockStockResearchProvider;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchReportServiceTest {

    private final FinancialDataValidator validator = new FinancialDataValidator(
            Clock.fixed(Instant.parse("2026-09-26T06:00:00Z"), ZoneId.of("Asia/Kolkata")));
    private final ResearchReportService service =
            new ResearchReportService(mock(StockResearchService.class), validator, new SectorClassifier());

    static StockResearchReport hdfcReport(FinancialDataValidator validator) {
        FinancialSummaryResult financials = FinancialSummaryResult.success(
                new FinancialMetricsService().summarize(ResearchFixtures.hdfcReported()));
        HistoricalPerformanceResult history = HistoricalPerformanceResult.success(
                new HistoricalPerformanceService().analyze(new PriceHistory("HDFCBANK", "NSE", "INR", List.of(
                        new PricePoint(LocalDate.of(2023, 12, 29), new BigDecimal("100")),
                        new PricePoint(LocalDate.of(2024, 12, 31), new BigDecimal("80")),
                        new PricePoint(LocalDate.of(2025, 12, 31), new BigDecimal("120")),
                        new PricePoint(LocalDate.of(2026, 9, 25), new BigDecimal("126"))),
                        "test series", new DataProvenance("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER,
                        DataFreshness.HISTORICAL, Instant.now(), null, null, null, "note.")), 5));
        return new ResearchReportService(mock(StockResearchService.class), validator, new SectorClassifier()).assemble("HDFC Bank",
                CompanyProfileResult.failure(ResearchStatus.PROVIDER_UNAVAILABLE, "Yahoo was down"),
                financials, history,
                ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "no source"));
    }

    @Test
    void keepsMissingShareholdingExplicitlyUnavailable() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.dataGaps()).anySatisfy(gap -> {
            assertThat(gap.area()).isEqualTo("Shareholding");
            assertThat(gap.reason()).contains("Shareholding data was not available from the configured provider.");
        });
    }

    @Test
    void identifiesTheLatestReportedQuarterFromProviderData() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.latestReportedQuarter().label()).isEqualTo("Q1 FY27");
        assertThat(report.generatedOn()).isEqualTo(LocalDate.of(2026, 9, 26));
    }

    @Test
    void derivesObservationsOnlyFromCalculatedValuesWithNeutralWording() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.observations()).extracting(Observation::statement).contains(
                "Net profit rose 4.65% (FY26 vs the prior fiscal year).",
                "Net profit grew at a compound annual rate of 12.47% over FY23 to FY26 (3 years).",
                "Total debt was 0.81x shareholders' equity at the end of FY26.",
                "The largest peak-to-trough fall in the window was -20.00% (peak 2023-12-29, trough 2024-12-31).",
                "2026 year-to-date (to 2026-09-25, a partial year) the price return was 5.00%.");
        assertThat(report.observations()).allSatisfy(o -> {
            assertThat(o.basis()).startsWith("calculated");
            assertThat(o.statement()).doesNotContainIgnoringCase("accelerat").doesNotContainIgnoringCase("momentum");
        });
    }

    @Test
    void withholdsEveryObservationThatDependsOnAConflictingField() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.withheldObservations()).extracting(WithheldObservation::subject).contains(
                "Revenue (FY26 vs the prior fiscal year)",
                "Revenue CAGR (FY23 to FY26 (3 years))",
                "Revenue quarter-on-quarter (Q1 FY27 vs Q4 FY26, not year-on-year)");
        assertThat(report.withheldObservations()).allSatisfy(w -> assertThat(w.reason())
                .isIn(WithheldObservation.Reason.DATA_CONFLICT, WithheldObservation.Reason.SECTOR_CONTEXT));
        assertThat(report.withheldTopics()).contains("revenue growth");
        assertThat(report.observations()).allSatisfy(o -> assertThat(o.inputFields())
                .doesNotContainAnyElementsOf(report.conflictedFields()));
        assertThat(report.conflictedFields()).contains("financialData.totalRevenue",
                "fundamentals-timeseries.annualTotalRevenue", "earnings.financialsChart.quarterly.revenue");
    }

    @Test
    void appliesNoSectorContextWhenTheSectorIsUnknown() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.sectorContext().group()).isEqualTo(IndustryGroup.UNKNOWN);
        assertThat(report.sectorContext().sectorMetricsNotAvailable()).isEmpty();
    }

    @Test
    void turnsAFailedSectionIntoADataGapInsteadOfDroppingTheReport() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.dataGaps()).anySatisfy(gap -> {
            assertThat(gap.area()).isEqualTo("Company");
            assertThat(gap.reason()).isEqualTo("Yahoo was down");
        });
        assertThat(report.companyName()).isEqualTo("HDFC Bank Limited");
    }

    @Test
    void buildStillProducesAReportWhenOneCapabilityFails() {
        MockStockResearchProvider provider = new MockStockResearchProvider();
        StockResearchService research = new StockResearchService(provider,
                symbol -> {
                    throw new IllegalStateException("financials provider crashed");
                },
                new UnsupportedShareholdingProvider(), provider,
                new FinancialMetricsService(), new HistoricalPerformanceService());

        StockResearchReport report = new ResearchReportService(research, validator, new SectorClassifier()).build("INFY");

        assertThat(report.financials().success()).isFalse();
        assertThat(report.dataGaps()).anySatisfy(gap -> assertThat(gap.area()).isEqualTo("Financials"));
        assertThat(report.companyProfile().success()).isTrue();
        assertThat(report.historicalPerformance().success()).isTrue();
    }

    @Test
    void fetchesTheFourSectionsConcurrently() {
        // Each fetch waits until all four have started: run one after another, the first would time out.
        CountDownLatch allStarted = new CountDownLatch(4);
        StockResearchService research = mock(StockResearchService.class);
        when(research.getCompanyProfile(anyString())).thenAnswer(call -> {
            awaitOthers(allStarted);
            return CompanyProfileResult.failure(ResearchStatus.PROVIDER_UNAVAILABLE, "down");
        });
        when(research.getFinancialSummary(anyString())).thenAnswer(call -> {
            awaitOthers(allStarted);
            return FinancialSummaryResult.failure(ResearchStatus.PROVIDER_UNAVAILABLE, "down");
        });
        when(research.getHistoricalPerformance(anyString(), anyInt())).thenAnswer(call -> {
            awaitOthers(allStarted);
            return HistoricalPerformanceResult.failure(ResearchStatus.PROVIDER_UNAVAILABLE, "down");
        });
        when(research.getShareholding(anyString())).thenAnswer(call -> {
            awaitOthers(allStarted);
            return ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "no source");
        });

        StockResearchReport report = new ResearchReportService(research, validator, new SectorClassifier()).build("INFY");

        assertThat(report.dataGaps()).extracting(DataGap::area)
                .contains("Company", "Financials", "Share price history", "Shareholding");
    }

    private static void awaitOthers(CountDownLatch allStarted) throws InterruptedException {
        allStarted.countDown();
        if (!allStarted.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("the four sections were not fetched concurrently");
        }
    }

    @Test
    void listsSourcesWithTheirPeriodAndPublicationDates() {
        StockResearchReport report = hdfcReport(validator);

        assertThat(report.sources()).anySatisfy(source -> {
            assertThat(source.covers()).contains("Financial statements");
            assertThat(source.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(source.publishedAt()).isEqualTo(LocalDate.of(2026, 7, 18));
        });
    }

    @Test
    void carriesDataConflictsIntoTheReport() {
        assertThat(hdfcReport(validator).dataQualityIssues())
                .anySatisfy(issue -> assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT));
    }

    @Test
    void usesTheRequestedNameWhenNoSectionResolvedTheCompany() {
        StockResearchReport report = service.assemble("NOPE",
                CompanyProfileResult.failure(ResearchStatus.SYMBOL_NOT_FOUND, "x"),
                FinancialSummaryResult.failure(ResearchStatus.SYMBOL_NOT_FOUND, "x"),
                HistoricalPerformanceResult.failure(ResearchStatus.SYMBOL_NOT_FOUND, "x"),
                ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "x"));

        assertThat(report.symbol()).isEqualTo("NOPE");
        assertThat(report.observations()).isEmpty();
        assertThat(report.latestReportedQuarter().known()).isFalse();
    }
}
