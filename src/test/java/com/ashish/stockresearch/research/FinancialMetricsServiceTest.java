package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.ashish.stockresearch.research.ResearchFixtures.CALENDAR;
import static com.ashish.stockresearch.research.ResearchFixtures.crore;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcAnnual;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcHeadline;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcReported;
import static com.ashish.stockresearch.research.ResearchFixtures.missing;
import static com.ashish.stockresearch.research.ResearchFixtures.quarter;
import static com.ashish.stockresearch.research.ResearchFixtures.reported;
import static com.ashish.stockresearch.research.ResearchFixtures.year;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialMetricsServiceTest {

    private final FinancialMetricsService service = new FinancialMetricsService();

    @Test
    void calculatesYearOnYearGrowthForTheLatestFiscalYear() {
        AnnualRatios fy26 = last(service.summarize(hdfcReported()).calculated().annual());

        assertThat(fy26.period().label()).isEqualTo("FY26");
        assertThat(fy26.revenueGrowthYoyPercent().value()).isEqualByComparingTo("4.73");
        assertThat(fy26.netProfitGrowthYoyPercent().value()).isEqualByComparingTo("4.65");
        assertThat(fy26.revenueGrowthYoyPercent().calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(fy26.revenueGrowthYoyPercent().inputFields()).containsExactly("fundamentals-timeseries.annualTotalRevenue");
        assertThat(fy26.revenueGrowthYoyPercent().calculation()).isEqualTo("(FY26 revenue / FY25 revenue - 1) x 100");
    }

    @Test
    void calculatesCagrAcrossTheAvailableFiscalYears() {
        FinancialSummary summary = service.summarize(hdfcReported());
        FinancialDataPoint revenueCagr = summary.calculated().revenueCagrPercent();

        assertThat(revenueCagr.value()).isEqualByComparingTo("16.90");
        assertThat(summary.calculated().netProfitCagrPercent().value()).isEqualByComparingTo("12.47");
        assertThat(revenueCagr.period().type()).isEqualTo(PeriodType.MULTI_YEAR);
        assertThat(revenueCagr.period().label()).isEqualTo("FY23 to FY26 (3 years)");
    }

    @Test
    void calculatesMarginsAndReturnsOnAverageBalances() {
        List<AnnualRatios> annual = service.summarize(hdfcReported()).calculated().annual();
        AnnualRatios fy23 = annual.get(0);
        AnnualRatios fy26 = last(annual);

        assertThat(fy26.netProfitMarginPercent().value()).isEqualByComparingTo("36.60");
        assertThat(fy26.returnOnEquityPercent().value()).isEqualByComparingTo("8.90");
        assertThat(fy26.returnOnEquityPercent().calculation()).contains("average shareholders' equity (FY25 and FY26 closing)");
        // The first year has no opening balance, so ROE falls back to closing equity - and says so.
        assertThat(fy23.returnOnEquityPercent().value()).isEqualByComparingTo("17.01");
        assertThat(fy23.returnOnEquityPercent().calculation()).contains("closing shareholders' equity").contains("not averaged");
    }

    @Test
    void reportsFirstYearGrowthAsUnavailableNotZero() {
        AnnualRatios fy23 = service.summarize(hdfcReported()).calculated().annual().get(0);

        assertThat(fy23.revenueGrowthYoyPercent().available()).isFalse();
        assertThat(fy23.revenueGrowthYoyPercent().value()).isNull();
        assertThat(fy23.revenueGrowthYoyPercent().unavailableReason()).contains("No prior fiscal year");
    }

    @Test
    void leavesAMarginUnavailableWhenAnInputIsMissingAndSaysWhy() {
        AnnualRatios fy26 = last(service.summarize(hdfcReported()).calculated().annual());

        // Yahoo publishes no operating income for banks.
        assertThat(fy26.operatingMarginPercent().available()).isFalse();
        assertThat(fy26.operatingMarginPercent().unavailableReason()).contains("did not publish");
    }

    @Test
    void calculatesTtmNetMarginFromReportedTtmFigures() {
        FinancialDataPoint margin = service.summarize(hdfcReported()).calculated().netProfitMarginTtmPercent();

        assertThat(margin.value()).isEqualByComparingTo("26.79");
        assertThat(margin.period().label()).isEqualTo("TTM to Q1 FY27 (ending 2026-06-30)");
    }

    @Test
    void calculatesQuarterOnQuarterGrowthOnlyForConsecutiveQuarters() {
        FinancialSummary consecutive = service.summarize(hdfcReported());
        assertThat(consecutive.calculated().latestQuarterRevenueGrowthQoqPercent().value()).isEqualByComparingTo("0.16");
        assertThat(consecutive.calculated().latestQuarterRevenueGrowthQoqPercent().period().label()).isEqualTo("Q1 FY27");

        FinancialSummary gap = service.summarize(reported(hdfcHeadline(), List.of(
                quarter(LocalDate.of(2025, 12, 31), "45868.84", "18653.75"),
                quarter(LocalDate.of(2026, 6, 30), "46355.55", "19059.72")), hdfcAnnual(),
                CALENDAR.quarter(LocalDate.of(2026, 6, 30))));
        assertThat(gap.calculated().latestQuarterRevenueGrowthQoqPercent().available()).isFalse();
        assertThat(gap.calculated().latestQuarterRevenueGrowthQoqPercent().unavailableReason())
                .contains("not consecutive");
    }

    @Test
    void refusesCagrWithFewerThanTwoYearsOfData() {
        FinancialSummary summary = service.summarize(reported(hdfcHeadline(), List.of(),
                List.of(year(2026, "192566.78", "70479.34", "816739.56", "5262119.32")),
                CALENDAR.quarter(LocalDate.of(2026, 6, 30))));

        assertThat(summary.calculated().revenueCagrPercent().available()).isFalse();
        assertThat(summary.dataGaps()).anySatisfy(gap -> assertThat(gap.item()).isEqualTo("revenueCagrPercent"));
    }

    @Test
    void listsEveryUnavailableHeadlineMetricAsADataGap() {
        FinancialSummary summary = service.summarize(hdfcReported());

        assertThat(summary.dataGaps()).extracting(gap -> gap.item())
                .contains("ebitda", "freeCashFlow", "debtToEquityPercent", "returnOnCapitalEmployedPercent");
    }

    @Test
    void recordsAnUnknownLatestQuarterAsAGapInsteadOfGuessingIt() {
        FinancialSummary summary = service.summarize(reported(hdfcHeadline(), List.of(), hdfcAnnual(),
                ReportingPeriod.unknown()));

        assertThat(summary.dataGaps()).anySatisfy(gap -> {
            assertThat(gap.item()).isEqualTo("latestReportedQuarter");
            assertThat(gap.reason()).contains("UNKNOWN");
        });
    }

    @Test
    void reportsConflictingRevenueDefinitionsAsADataConflictWithBothValuesAndFields() {
        // Live HDFC data: TTM totalRevenue 2.95 lakh crore vs FY26 annualTotalRevenue 1.93 lakh crore.
        FinancialSummary summary = service.summarize(hdfcReported());

        assertThat(summary.dataQualityIssues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
            assertThat(issue.message()).contains("₹2.95 lakh crore").contains("₹1.93 lakh crore")
                    .contains("Neither is chosen");
            assertThat(issue.affectedFields()).containsExactly(
                    "financialData.totalRevenue", "fundamentals-timeseries.annualTotalRevenue");
        });
    }

    @Test
    void comparesTtmRevenueWithTheSumOfTheSameFourQuarters() {
        // Same period, TTM to Q1 FY27: 2,94,964 crore vs 1,84,406 crore from the four quarters.
        FinancialSummary summary = service.summarize(hdfcReported());

        assertThat(summary.dataQualityIssues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
            assertThat(issue.message()).contains("sum of the four reported quarters Q2 FY26 to Q1 FY27")
                    .contains("₹1.84 lakh crore");
            assertThat(issue.affectedFields()).contains(
                    "financialData.totalRevenue", "earnings.financialsChart.quarterly.revenue");
        });
        // Net profit: 79,013 vs 75,576 crore is within tolerance - not a conflict.
        assertThat(summary.dataQualityIssues())
                .noneSatisfy(issue -> assertThat(issue.message()).startsWith("TTM net profit"));
    }

    @Test
    void doesNotFlagTheMarginsWhenTheCalculatedAndPublishedValuesAgree() {
        assertThat(service.summarize(hdfcReported()).dataQualityIssues())
                .noneSatisfy(issue -> assertThat(issue.message()).contains("net profit margin"));
    }

    @Test
    void calculatesDebtToEquityAsAMultipleFromSameDateBalances() {
        AnnualRatios fy26 = last(service.summarize(hdfcReported()).calculated().annual());

        // 6,64,364 / 8,16,740 = 0.81x - a multiple, never "81%".
        assertThat(fy26.debtToEquityMultiple().unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(fy26.debtToEquityMultiple().value()).isEqualByComparingTo("0.81");
        assertThat(fy26.debtToEquityMultiple().display()).isEqualTo("0.81x");
        assertThat(fy26.debtToEquityMultiple().inputFields()).containsExactly(
                "fundamentals-timeseries.annualTotalDebt", "fundamentals-timeseries.annualStockholdersEquity");
    }

    @Test
    void refusesToCombineAmountsInDifferentUnits() {
        var mixed = reported(hdfcHeadline(), List.of(), List.of(
                year(2025, "183864.11", "67350.83", "767689.14", "4818767.11"),
                new com.ashish.stockresearch.research.model.AnnualFinancials(
                        CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31)),
                        FinancialDataPoint.reported("annualRevenue", new java.math.BigDecimal("23000000000"),
                                com.ashish.stockresearch.research.model.ProviderUnit.WHOLE_CURRENCY_UNITS,
                                new java.math.BigDecimal("23000"), Unit.USD_MILLION,
                                CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31)), ResearchFixtures.SOURCE),
                        crore("annualNetProfit", "f", "70479.34", CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31))),
                        missing("a", "f", CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31))),
                        missing("b", "f", CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31))),
                        missing("c", "f", CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31))),
                        missing("d", "f", CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31))))),
                CALENDAR.quarter(LocalDate.of(2026, 6, 30)));

        AnnualRatios fy26 = last(service.summarize(mixed).calculated().annual());

        assertThat(fy26.revenueGrowthYoyPercent().available()).isFalse();
        assertThat(fy26.revenueGrowthYoyPercent().unavailableReason()).contains("different units");
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }
}
