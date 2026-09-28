package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.AnnualCrossCheckFigures;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.ashish.stockresearch.research.ResearchFixtures.CALENDAR;
import static com.ashish.stockresearch.research.ResearchFixtures.LATEST_QUARTER_END;
import static com.ashish.stockresearch.research.ResearchFixtures.SOURCE;
import static com.ashish.stockresearch.research.ResearchFixtures.consistentReported;
import static com.ashish.stockresearch.research.ResearchFixtures.crore;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcAnnual;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcHeadline;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcQuarters;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcReported;
import static com.ashish.stockresearch.research.ResearchFixtures.missing;
import static com.ashish.stockresearch.research.ResearchFixtures.percent;
import static com.ashish.stockresearch.research.ResearchFixtures.quarter;
import static com.ashish.stockresearch.research.ResearchFixtures.reported;
import static com.ashish.stockresearch.research.ResearchFixtures.year;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialMetricsServiceTest {

    private final FinancialMetricsService service = new FinancialMetricsService();

    // --- Calculations on data that passes every source check ---------------------------------

    @Test
    void calculatesYearOnYearGrowthAndRecordsTheExactInputs() {
        AnnualRatios fy26 = last(service.summarize(consistentReported()).calculated().annual());
        FinancialDataPoint revenueYoy = fy26.revenueGrowthYoyPercent();

        assertThat(fy26.period().label()).isEqualTo("FY26");
        assertThat(revenueYoy.value()).isEqualByComparingTo("4.73");
        assertThat(fy26.netProfitGrowthYoyPercent().value()).isEqualByComparingTo("4.65");
        assertThat(revenueYoy.status()).isEqualTo(DataStatus.VALID);
        assertThat(revenueYoy.calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(revenueYoy.formula()).isEqualTo(Formula.GROWTH_PERCENT);
        assertThat(revenueYoy.calculation()).isEqualTo("(FY26 revenue / FY25 revenue - 1) x 100");
        assertThat(revenueYoy.inputFields()).containsExactly("fundamentals-timeseries.annualTotalRevenue");
        assertThat(revenueYoy.inputs()).extracting(CalculationInput::name, CalculationInput::value,
                        CalculationInput::period)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FY25 revenue", new BigDecimal("183864.11"), "FY25"),
                        org.assertj.core.groups.Tuple.tuple("FY26 revenue", new BigDecimal("192566.78"), "FY26"));
    }

    @Test
    void calculatesCagrFromItsRecordedInputs() {
        FinancialSummary summary = service.summarize(consistentReported());
        FinancialDataPoint revenueCagr = summary.calculated().revenueCagrPercent();

        assertThat(revenueCagr.value()).isEqualByComparingTo("16.90");
        assertThat(summary.calculated().netProfitCagrPercent().value()).isEqualByComparingTo("12.47");
        assertThat(revenueCagr.period().type()).isEqualTo(PeriodType.MULTI_YEAR);
        assertThat(revenueCagr.period().label()).isEqualTo("FY23 to FY26 (3 years)");
        assertThat(revenueCagr.inputs()).extracting(CalculationInput::name)
                .containsExactly("FY23 revenue", "FY26 revenue", "years");
        // CAGR = (end / start)^(1 / years) - 1, recomputed here independently of the service.
        double start = revenueCagr.inputs().get(0).value().doubleValue();
        double end = revenueCagr.inputs().get(1).value().doubleValue();
        double years = revenueCagr.inputs().get(2).value().doubleValue();
        assertThat(revenueCagr.value().doubleValue())
                .isCloseTo((Math.pow(end / start, 1 / years) - 1) * 100, org.assertj.core.data.Offset.offset(0.005));
    }

    @Test
    void calculatesMarginsAndReturnsOnAverageBalances() {
        List<AnnualRatios> annual = service.summarize(consistentReported()).calculated().annual();
        AnnualRatios fy23 = annual.get(0);
        AnnualRatios fy26 = last(annual);

        assertThat(fy26.netProfitMarginPercent().value()).isEqualByComparingTo("36.60");
        assertThat(fy26.returnOnEquityPercent().value()).isEqualByComparingTo("8.90");
        assertThat(fy26.returnOnEquityPercent().calculation()).contains("average shareholders' equity (FY25 and FY26 closing)");
        assertThat(fy26.returnOnEquityPercent().inputs()).hasSize(3);
        // The first year has no opening balance, so ROE falls back to closing equity - and says so.
        assertThat(fy23.returnOnEquityPercent().value()).isEqualByComparingTo("17.01");
        assertThat(fy23.returnOnEquityPercent().calculation()).contains("closing shareholders' equity").contains("not averaged");
        assertThat(fy23.returnOnEquityPercent().inputs()).hasSize(2);
    }

    @Test
    void reportsFirstYearGrowthAsUnavailableNotZero() {
        AnnualRatios fy23 = service.summarize(consistentReported()).calculated().annual().get(0);

        assertThat(fy23.revenueGrowthYoyPercent().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(fy23.revenueGrowthYoyPercent().value()).isNull();
        assertThat(fy23.revenueGrowthYoyPercent().statusReason()).contains("No prior fiscal year");
    }

    @Test
    void leavesAMarginUnavailableWhenAnInputIsMissingAndSaysWhy() {
        AnnualRatios fy26 = last(service.summarize(consistentReported()).calculated().annual());

        // Yahoo publishes no operating income for banks.
        assertThat(fy26.operatingMarginPercent().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(fy26.operatingMarginPercent().statusReason()).contains("did not publish");
    }

    @Test
    void calculatesTtmNetMarginFromReportedTtmFigures() {
        FinancialDataPoint margin = service.summarize(consistentReported()).calculated().netProfitMarginTtmPercent();

        assertThat(margin.value()).isEqualByComparingTo("42.85");
        assertThat(margin.period().label()).isEqualTo("TTM to Q1 FY27 (ending 2026-06-30)");
    }

    @Test
    void calculatesQuarterOnQuarterGrowthOnlyForConsecutiveQuarters() {
        FinancialSummary consecutive = service.summarize(consistentReported());
        assertThat(consecutive.calculated().latestQuarterRevenueGrowthQoqPercent().value()).isEqualByComparingTo("0.16");
        assertThat(consecutive.calculated().latestQuarterRevenueGrowthQoqPercent().period().label()).isEqualTo("Q1 FY27");

        FinancialSummary gap = service.summarize(reported(consistentReported().headline(), List.of(
                quarter(LocalDate.of(2025, 12, 31), "45868.84", "18653.75"),
                quarter(LocalDate.of(2026, 6, 30), "46355.55", "19059.72")), hdfcAnnual(),
                CALENDAR.quarter(LocalDate.of(2026, 6, 30))));
        assertThat(gap.calculated().latestQuarterRevenueGrowthQoqPercent().available()).isFalse();
        assertThat(gap.calculated().latestQuarterRevenueGrowthQoqPercent().statusReason())
                .contains("not consecutive");
    }

    @Test
    void calculatesDebtToEquityAsAMultipleFromSameDateBalances() {
        AnnualRatios fy26 = last(service.summarize(consistentReported()).calculated().annual());

        // 6,64,364 / 8,16,740 = 0.81x - a multiple, never "81%".
        assertThat(fy26.debtToEquityMultiple().unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(fy26.debtToEquityMultiple().value()).isEqualByComparingTo("0.81");
        assertThat(fy26.debtToEquityMultiple().display()).isEqualTo("0.81x");
        assertThat(fy26.debtToEquityMultiple().inputFields()).containsExactly(
                "fundamentals-timeseries.annualTotalDebt", "fundamentals-timeseries.annualStockholdersEquity");
    }

    /** The invariant behind every figure shown: it reproduces from its recorded inputs, which match the source. */
    @Test
    void everyValidCalculatedValueReproducesFromItsInputsAndTheInputsMatchTheSource() {
        FinancialSummary summary = service.summarize(consistentReported());
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        summary.reported().headline().points().forEach(sources::add);
        summary.reported().recentQuarters().forEach(q -> q.points().forEach(sources::add));
        summary.reported().annualHistory().forEach(y -> y.points().forEach(sources::add));

        List<FinancialDataPoint> valid = summary.calculated().points().stream().filter(FinancialDataPoint::available)
                .toList();
        assertThat(valid).hasSizeGreaterThan(15);
        assertThat(valid).allSatisfy(point -> {
            assertThat(point.inputs()).as(point.metric()).isNotEmpty();
            assertThat(CalculationVerifier.problem(point, sources)).as(point.metric()).isEmpty();
        });
        assertThat(summary.dataQualityIssues())
                .noneMatch(issue -> issue.type() == DataQualityIssue.Type.CALCULATION_INVALID);
    }

    @Test
    void refusesCagrWithFewerThanTwoYearsOfData() {
        FinancialSummary summary = service.summarize(reported(consistentReported().headline(), List.of(),
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

    // --- DATA_CONFLICT detection and propagation ---------------------------------------------

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
    void keepsConflictingReportedValuesVisibleButMarksThemUnusable() {
        FinancialSummary summary = service.summarize(hdfcReported());

        FinancialDataPoint ttm = summary.reported().headline().revenue();
        FinancialDataPoint fy26 = last(summary.reported().annualHistory()).revenue();
        assertThat(ttm.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(ttm.value()).isEqualByComparingTo("294964.43");
        assertThat(fy26.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(fy26.value()).isEqualByComparingTo("192566.78");
        assertThat(summary.reported().recentQuarters()).allSatisfy(q ->
                assertThat(q.revenue().status()).isEqualTo(DataStatus.DATA_CONFLICT));
        // Yahoo derives these from the disputed revenue figure, so they are covered by the conflict too.
        assertThat(summary.reported().headline().netProfitMarginPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(summary.reported().headline().operatingMarginPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(summary.reported().headline().quarterlyRevenueGrowthYoyPercent().status())
                .isEqualTo(DataStatus.DATA_CONFLICT);
        // Figures not involved stay VALID.
        assertThat(summary.reported().headline().netProfit().status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void neverCalculatesAMetricFromAConflictingInput() {
        FinancialSummary summary = service.summarize(hdfcReported());
        AnnualRatios fy26 = last(summary.calculated().annual());

        for (FinancialDataPoint dependent : List.of(summary.calculated().revenueCagrPercent(),
                fy26.revenueGrowthYoyPercent(), fy26.netProfitMarginPercent(),
                summary.calculated().netProfitMarginTtmPercent(),
                summary.calculated().latestQuarterRevenueGrowthQoqPercent())) {
            assertThat(dependent.status()).as(dependent.metric()).isEqualTo(DataStatus.DATA_CONFLICT);
            assertThat(dependent.value()).as(dependent.metric()).isNull();
            assertThat(dependent.display()).isEqualTo("DATA_CONFLICT");
            assertThat(dependent.statusReason()).as(dependent.metric()).contains("not calculated")
                    .contains("DATA_CONFLICT");
        }
        assertThat(summary.calculated().revenueCagrPercent().statusReason())
                .contains("fundamentals-timeseries.annualTotalRevenue");
        // Metrics that do not depend on revenue are still calculated.
        assertThat(fy26.netProfitGrowthYoyPercent().value()).isEqualByComparingTo("4.65");
        assertThat(fy26.returnOnEquityPercent().value()).isEqualByComparingTo("8.90");
        assertThat(summary.calculated().netProfitCagrPercent().value()).isEqualByComparingTo("12.47");
    }

    @Test
    void conflictsBetweenTheTwoAnnualFeedsBlockEveryDependentMetric() {
        ReportedFinancials base = consistentReported();
        ReportingPeriod fy25 = CALENDAR.fiscalYear(LocalDate.of(2025, 3, 31));
        ReportingPeriod fy26 = CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31));
        // HDFC's real second feed: FY25 net profit 70,792 vs 67,351 crore, FY26 revenue matching within 1%.
        List<AnnualCrossCheckFigures> crossCheck = List.of(
                new AnnualCrossCheckFigures(fy25,
                        crore("annualRevenue", "earnings.financialsChart.yearly.revenue", "183900.00", fy25),
                        crore("annualNetProfit", "earnings.financialsChart.yearly.earnings", "70792.25", fy25)),
                new AnnualCrossCheckFigures(fy26,
                        crore("annualRevenue", "earnings.financialsChart.yearly.revenue", "191218.61", fy26),
                        crore("annualNetProfit", "earnings.financialsChart.yearly.earnings", "70479.34", fy26)));
        ReportedFinancials twoFeeds = new ReportedFinancials(base.symbol(), base.exchange(), base.companyName(),
                base.reportingCurrency(), base.latestReportedQuarter(), base.latestFiscalYear(), base.headline(),
                base.recentQuarters(), base.annualHistory(), crossCheck, base.dataGaps(), base.provenance());

        FinancialSummary summary = service.summarize(twoFeeds);

        assertThat(summary.dataQualityIssues()).filteredOn(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .singleElement().satisfies(issue -> {
                    assertThat(issue.message()).contains("net profit").contains("FY25").doesNotContain("FY26:");
                    assertThat(issue.affectedFields()).containsExactly("fundamentals-timeseries.annualNetIncome",
                            "earnings.financialsChart.yearly.earnings");
                });
        AnnualRatios last = last(summary.calculated().annual());
        assertThat(last.netProfitGrowthYoyPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(last.returnOnEquityPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(summary.calculated().netProfitCagrPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(last.revenueGrowthYoyPercent().status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void doesNotTrustAProviderGrowthRateThatContradictsTheReportedQuarters() {
        List<QuarterlyResult> quarters = new ArrayList<>(List.of(quarter(LocalDate.of(2025, 6, 30), "40000.00", "16000.00")));
        quarters.addAll(hdfcQuarters());
        ReportedFinancials base = consistentReported();
        // Q1 FY27 vs Q1 FY26 from the quarters: 46,355.55 / 40,000 - 1 = 15.89%. Yahoo says 25%.
        var headline = base.headline().map(p -> p.metric().equals("quarterlyRevenueGrowthYoyPercent")
                ? percent("quarterlyRevenueGrowthYoyPercent", "financialData.revenueGrowth", "25.00",
                        CALENDAR.quarter(LATEST_QUARTER_END)).derivedFrom(List.of("financialData.totalRevenue"))
                : p);

        FinancialSummary summary = service.summarize(reported(headline, quarters, hdfcAnnual(),
                CALENDAR.quarter(LATEST_QUARTER_END)));

        assertThat(summary.dataQualityIssues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
            assertThat(issue.message()).contains("25.00%").contains("15.89%").contains("not used");
        });
        assertThat(summary.reported().headline().quarterlyRevenueGrowthYoyPercent().status())
                .isEqualTo(DataStatus.DATA_CONFLICT);
    }

    @Test
    void doesNotFlagTheMarginsWhenTheCalculatedAndPublishedValuesAgree() {
        assertThat(service.summarize(hdfcReported()).dataQualityIssues())
                .noneSatisfy(issue -> assertThat(issue.message()).contains("Calculated TTM net profit margin"));
    }

    // --- Currencies ----------------------------------------------------------------------------

    @Test
    void refusesToCombineAmountsInDifferentUnits() {
        ReportingPeriod fy26 = CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31));
        var mixed = reported(consistentReported().headline(), List.of(), List.of(
                year(2025, "183864.11", "67350.83", "767689.14", "4818767.11"),
                new AnnualFinancials(fy26,
                        FinancialDataPoint.reported("annualRevenue", new BigDecimal("23000000000"),
                                ProviderUnit.WHOLE_CURRENCY_UNITS, new BigDecimal("23000"), Unit.USD_MILLION, fy26,
                                SOURCE.withField("fundamentals-timeseries.annualTotalRevenue")),
                        crore("annualNetProfit", "f", "70479.34", fy26),
                        missing("a", "f", fy26), missing("b", "f", fy26), missing("c", "f", fy26),
                        missing("d", "f", fy26), missing("e", "f", fy26), missing("g", "f", fy26),
                        missing("h", "f", fy26))),
                CALENDAR.quarter(LocalDate.of(2026, 6, 30)));

        FinancialSummary summary = service.summarize(mixed);
        AnnualRatios last = last(summary.calculated().annual());

        assertThat(last.revenueGrowthYoyPercent().available()).isFalse();
        assertThat(last.revenueGrowthYoyPercent().statusReason()).contains("different units")
                .contains("never converted");
        assertThat(summary.dataQualityIssues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_QUALITY_WARNING);
            assertThat(issue.message()).contains("different currencies").contains("never converts");
        });
    }

    @Test
    void neverComparesTtmWithQuartersReportedInAnotherCurrency() {
        // The Infosys shape: headline in USD, quarterly charts in INR. Labelling both USD produced a
        // spurious 98.9% "conflict"; now the figures are simply never compared.
        ReportingPeriod ttm = CALENDAR.trailingTwelveMonths(LATEST_QUARTER_END);
        var usdHeadline = consistentReported().headline().map(p -> p.metric().equals("revenue")
                ? FinancialDataPoint.reported("revenue", new BigDecimal("20298999808"), ProviderUnit.WHOLE_CURRENCY_UNITS,
                        new BigDecimal("20299.00"), Unit.USD_MILLION, ttm, SOURCE.withField("financialData.totalRevenue"))
                : p);

        FinancialSummary summary = service.summarize(reported(usdHeadline, hdfcQuarters(), List.of(),
                CALENDAR.quarter(LATEST_QUARTER_END)));

        assertThat(summary.dataQualityIssues()).noneMatch(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT);
        assertThat(summary.dataQualityIssues()).anySatisfy(issue ->
                assertThat(issue.message()).contains("headline (TTM) figures in USD").contains("quarterly figures in INR"));
        assertThat(summary.calculated().netProfitMarginTtmPercent().statusReason()).contains("different units");
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    // --- Cash conversion and ROCE -------------------------------------------------------------

    /** Four fiscal years whose OCF/PAT and ROCE can be checked by hand. */
    private static List<AnnualFinancials> capitalAndCashYears(String fy25OperatingCashFlow, String fy26CurrentLiabilities) {
        return List.of(
                ResearchFixtures.withCapitalAndCash(year(2023, "1000", "100", "800", "1500"), "150", "300", "90"),
                ResearchFixtures.withCapitalAndCash(year(2024, "1100", "110", "880", "1600"), "160", "320", "130"),
                ResearchFixtures.withCapitalAndCash(year(2025, "1200", "120", "960", "1700"), "180", "340",
                        fy25OperatingCashFlow),
                ResearchFixtures.withCapitalAndCash(year(2026, "1300", "130", "1040", "1800"), "200",
                        fy26CurrentLiabilities, "150"));
    }

    private FinancialSummary summarizeYears(List<AnnualFinancials> annual) {
        return service.summarize(reported(consistentReported().headline(), hdfcQuarters(), annual,
                CALENDAR.quarter(LATEST_QUARTER_END)));
    }

    @Test
    void calculatesOperatingCashFlowToNetProfitOverTheLastThreeYearsAndTheVerifierReproducesIt() {
        FinancialDataPoint ocfToPat = summarizeYears(capitalAndCashYears("120", "360")).calculated().ocfToPat3yMultiple();

        // (130 + 120 + 150) / (110 + 120 + 130) = 400 / 360 = 1.11x
        assertThat(ocfToPat.status()).isEqualTo(DataStatus.VALID);
        assertThat(ocfToPat.display()).isEqualTo("1.11x");
        assertThat(ocfToPat.unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(ocfToPat.formula()).isEqualTo(Formula.SUM_RATIO);
        assertThat(ocfToPat.period().label()).isEqualTo("FY24 to FY26 (3 years)");
        assertThat(ocfToPat.inputs()).extracting(input -> input.name()).containsExactly(
                "FY24 operating cash flow", "FY25 operating cash flow", "FY26 operating cash flow",
                "FY24 net profit", "FY25 net profit", "FY26 net profit");
        assertThat(CalculationVerifier.problem(ocfToPat, new CalculationVerifier.SourceValues())).isEmpty();
    }

    @Test
    void givesNoFiveYearCashConversionWhenOnlyFourYearsAreReported() {
        FinancialSummary summary = summarizeYears(capitalAndCashYears("120", "360"));
        FinancialDataPoint fiveYear = summary.calculated().ocfToPat5yMultiple();

        assertThat(fiveYear.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(fiveYear.value()).isNull();
        assertThat(fiveYear.statusReason()).contains("needs 5 fiscal years").contains("4 available (FY23 to FY26)");
        assertThat(summary.dataGaps()).anySatisfy(gap -> assertThat(gap.item()).isEqualTo("ocfToPat5yMultiple"));
    }

    @Test
    void aMissingYearMakesCashConversionUnavailableNotARatioOverFewerYears() {
        FinancialDataPoint ocfToPat = summarizeYears(capitalAndCashYears(null, "360")).calculated().ocfToPat3yMultiple();

        assertThat(ocfToPat.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(ocfToPat.value()).isNull();
        assertThat(ocfToPat.statusReason()).contains("operatingCashFlow").contains("FY25");
    }

    @Test
    void aDisputedNetProfitYearBlocksCashConversion() {
        List<AnnualFinancials> years = new java.util.ArrayList<>(capitalAndCashYears("120", "360"));
        AnnualFinancials fy25 = years.get(2);
        years.set(2, new AnnualFinancials(fy25.period(), fy25.revenue(), fy25.netProfit().inConflict("two feeds disagree"),
                fy25.operatingIncome(), fy25.shareholdersEquity(), fy25.totalAssets(), fy25.totalDebt(), fy25.ebit(),
                fy25.currentLiabilities(), fy25.operatingCashFlow()));

        FinancialDataPoint ocfToPat = summarizeYears(years).calculated().ocfToPat3yMultiple();

        assertThat(ocfToPat.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(ocfToPat.value()).isNull();
    }

    @Test
    void calculatesRoceOnAverageCapitalEmployedAndOnClosingWithoutAPriorYear() {
        List<AnnualRatios> annual = summarizeYears(capitalAndCashYears("120", "360")).calculated().annual();
        FinancialDataPoint fy26 = last(annual).returnOnCapitalEmployedPercent();
        FinancialDataPoint fy23 = annual.get(0).returnOnCapitalEmployedPercent();

        // capital employed 1800 - 360 = 1440 and 1700 - 340 = 1360, average 1400; 200 / 1400 = 14.29%
        assertThat(fy26.status()).isEqualTo(DataStatus.VALID);
        assertThat(fy26.display()).isEqualTo("14.29%");
        assertThat(fy26.formula()).isEqualTo(Formula.RETURN_ON_CAPITAL_EMPLOYED_PERCENT);
        assertThat(fy26.inputs()).hasSize(5);
        assertThat(fy26.calculation()).contains("average capital employed (FY25 and FY26 closing)")
                .contains("total assets - current liabilities");
        // FY23 has no prior year: 150 / (1500 - 300) = 12.50%, and it says so.
        assertThat(fy23.display()).isEqualTo("12.50%");
        assertThat(fy23.calculation()).contains("closing capital employed at FY23");
        assertThat(CalculationVerifier.problem(fy26, new CalculationVerifier.SourceValues())).isEmpty();
    }

    @Test
    void givesNoRoceWhenCurrentLiabilitiesAreNotPublished() {
        FinancialDataPoint fy26 = last(summarizeYears(capitalAndCashYears("120", null)).calculated().annual())
                .returnOnCapitalEmployedPercent();

        assertThat(fy26.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(fy26.statusReason()).contains("currentLiabilities");
    }
}
