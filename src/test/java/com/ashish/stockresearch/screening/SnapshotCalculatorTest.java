package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.ResearchFixtures;
import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.ashish.stockresearch.research.ResearchFixtures.crore;
import static com.ashish.stockresearch.research.ResearchFixtures.year;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCalculatorTest {

    private static CalculationVerifier.SourceValues sources(List<AnnualFinancials> annual) {
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        annual.forEach(y -> y.points().forEach(sources::add));
        return sources;
    }

    private static List<AnnualFinancials> fiveYears() {
        return List.of(year(2022, "80000", "8000", "40000", "90000"), year(2023, "100000", "10000", "50000", "100000"),
                year(2024, "110000", "12000", "55000", "110000"), year(2025, "121000", "13000", "60000", "120000"),
                year(2026, "133100", "14641", "65000", "130000"));
    }

    @Test
    void calculatesCagrOverExactlyTheLastThreeFiscalYearsEvenWhenMoreAreAvailable() {
        List<AnnualFinancials> annual = fiveYears();

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 3, AnnualFinancials::revenue, "revenue",
                "revenueCagr3yPercent", sources(annual));

        // FY23 100000 -> FY26 133100 is exactly 10% a year; FY22 is not part of the span.
        assertThat(cagr.status()).isEqualTo(DataStatus.VALID);
        assertThat(cagr.value()).isEqualByComparingTo("10.00");
        assertThat(cagr.formula()).isEqualTo(Formula.CAGR_PERCENT);
        assertThat(cagr.period().label()).isEqualTo("FY23 to FY26 (3 years)");
        assertThat(CalculationVerifier.problem(cagr, sources(annual))).isEmpty();
    }

    @Test
    void givesNoCagrFromFewerThanFourFiscalYears() {
        List<AnnualFinancials> annual = fiveYears().subList(2, 5);

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 3, AnnualFinancials::revenue, "revenue", "x",
                sources(annual));

        assertThat(cagr.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(cagr.value()).isNull();
        assertThat(cagr.statusReason()).contains("needs 4 fiscal years; 3 available");
    }

    @Test
    void givesNoCagrAcrossAMissingYear() {
        List<AnnualFinancials> annual = new ArrayList<>(fiveYears());
        annual.remove(2); // FY24

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 3, AnnualFinancials::revenue, "revenue", "x",
                sources(annual));

        assertThat(cagr.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(cagr.statusReason()).contains("consecutive").contains("FY23").contains("FY25");
    }

    @Test
    void aDisputedYearInTheSpanBlocksTheCagr() {
        List<AnnualFinancials> annual = new ArrayList<>(fiveYears());
        AnnualFinancials fy24 = annual.get(2);
        annual.set(2, fy24.map(point -> point.metric().equals("annualRevenue") ? point.inConflict("feeds disagree")
                : point));

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 3, AnnualFinancials::revenue, "revenue", "x",
                sources(annual));

        assertThat(cagr.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(cagr.value()).isNull();
    }

    @Test
    void averagesTheLastThreeYearsOfCalculatedRoeAndVerifiesTheMean() {
        List<AnnualFinancials> annual = fiveYears();
        List<AnnualRatios> ratios = new FinancialMetricsService().summarize(ResearchFixtures.reported(
                ResearchFixtures.consistentReported().headline(), List.of(), annual,
                ResearchFixtures.consistentReported().latestReportedQuarter())).calculated().annual();

        FinancialDataPoint mean = SnapshotCalculator.roeAverage(ratios, 3, sources(annual));

        List<FinancialDataPoint> lastThree = ratios.subList(2, 5).stream().map(AnnualRatios::returnOnEquityPercent).toList();
        BigDecimal expected = lastThree.stream().map(FinancialDataPoint::value).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP);
        assertThat(mean.status()).isEqualTo(DataStatus.VALID);
        assertThat(mean.calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(mean.formula()).isEqualTo(Formula.MEAN);
        assertThat(mean.value()).isEqualByComparingTo(expected);
        assertThat(mean.inputs()).hasSize(3);
        assertThat(mean.period().label()).isEqualTo("FY24 to FY26 (3 years)");
        assertThat(CalculationVerifier.problem(mean, sources(annual))).isEmpty();
    }

    @Test
    void anRoeYearThatIsNotValidBlocksTheAverage() {
        List<AnnualFinancials> annual = fiveYears();
        List<AnnualRatios> ratios = new ArrayList<>(new FinancialMetricsService().summarize(ResearchFixtures.reported(
                ResearchFixtures.consistentReported().headline(), List.of(), annual,
                ResearchFixtures.consistentReported().latestReportedQuarter())).calculated().annual());
        AnnualRatios fy25 = ratios.get(3);
        ratios.set(3, fy25.map(point -> point.metric().equals("returnOnEquityPercent")
                ? point.invalid("failed verification") : point));

        FinancialDataPoint mean = SnapshotCalculator.roeAverage(ratios, 3, sources(annual));

        assertThat(mean.status()).isEqualTo(DataStatus.INVALID);
        assertThat(mean.value()).isNull();
    }

    /** Six fiscal years, as the filings supply them: FY21 to FY26, revenue growing 10% a year from FY21. */
    private static List<AnnualFinancials> sixYears() {
        List<AnnualFinancials> annual = new ArrayList<>(List.of(year(2021, "82645", "7000", "35000", "85000")));
        annual.addAll(List.of(year(2022, "90910", "8000", "40000", "90000"), year(2023, "100000", "10000", "50000",
                "100000"), year(2024, "110000", "12000", "55000", "110000"), year(2025, "121000", "13000", "60000",
                "120000"), year(2026, "133100", "14641", "65000", "130000")));
        return annual;
    }

    private static List<AnnualRatios> ratios(List<AnnualFinancials> annual) {
        return new FinancialMetricsService().summarize(ResearchFixtures.reported(
                ResearchFixtures.consistentReported().headline(), List.of(), annual,
                ResearchFixtures.consistentReported().latestReportedQuarter())).calculated().annual();
    }

    @Test
    void calculatesAFiveYearCagrFromSixFiscalYears() {
        List<AnnualFinancials> annual = sixYears();

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 5, AnnualFinancials::revenue, "revenue",
                "revenueCagr5yPercent", sources(annual));

        // FY21 82,645 -> FY26 1,33,100: (133100 / 82645)^(1/5) - 1 = 10.00%
        assertThat(cagr.status()).isEqualTo(DataStatus.VALID);
        assertThat(cagr.value()).isEqualByComparingTo("10.00");
        assertThat(cagr.period().label()).isEqualTo("FY21 to FY26 (5 years)");
        assertThat(cagr.calculation()).isEqualTo("((FY26 revenue / FY21 revenue)^(1/5) - 1) x 100");
        assertThat(CalculationVerifier.problem(cagr, sources(annual))).isEmpty();
    }

    @Test
    void neverMeasuresAFiveYearCagrOverFewerYears() {
        List<AnnualFinancials> annual = fiveYears();

        FinancialDataPoint cagr = SnapshotCalculator.cagr(annual, 5, AnnualFinancials::revenue, "revenue", "x",
                sources(annual));

        assertThat(cagr.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(cagr.statusReason()).isEqualTo("a 5-year revenue CAGR needs 6 fiscal years; 5 available");
    }

    @Test
    void averagesFiveYearsOfRoeOnlyWhenEveryYearIsOnAverageEquity() {
        List<AnnualFinancials> six = sixYears();
        FinancialDataPoint mean = SnapshotCalculator.roeAverage(ratios(six), 5, sources(six));

        assertThat(mean.status()).isEqualTo(DataStatus.VALID);
        assertThat(mean.inputs()).hasSize(5);
        assertThat(mean.period().label()).isEqualTo("FY22 to FY26 (5 years)");
        assertThat(CalculationVerifier.problem(mean, sources(six))).isEmpty();

        // with five years, the first has no opening balance sheet: its ROE is on closing equity
        List<AnnualFinancials> five = fiveYears();
        FinancialDataPoint mixed = SnapshotCalculator.roeAverage(ratios(five), 5, sources(five));
        assertThat(mixed.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(mixed.statusReason()).isEqualTo("FY22 return on equity is on closing equity (no prior-year balance "
                + "sheet), so the 5 years are not on one basis");
    }

    @Test
    void expressesTheLatestYearsDividendsAsAShareOfItsOperatingCashFlow() {
        ReportingPeriod fy26 = year(2026, "1", "1", "1", "1").period();
        AnnualFinancials techm = withCash(year(2026, "52000", "4200", "27000", "55000"), "6172.00", "-4025.50", fy26);

        FinancialDataPoint payout = SnapshotCalculator.dividendsToOperatingCashFlow(List.of(techm), sources(List.of(techm)));

        assertThat(payout.status()).isEqualTo(DataStatus.VALID);
        assertThat(payout.value()).isEqualByComparingTo("65.22");
        assertThat(payout.period().label()).isEqualTo("FY26");
        assertThat(CalculationVerifier.problem(payout, sources(List.of(techm)))).isEmpty();
    }

    @Test
    void givesNoPayoutWhenDividendsPaidIsNotAnOutflow() {
        ReportingPeriod fy26 = year(2026, "1", "1", "1", "1").period();
        AnnualFinancials odd = withCash(year(2026, "52000", "4200", "27000", "55000"), "6172.00", "4025.50", fy26);

        FinancialDataPoint payout = SnapshotCalculator.dividendsToOperatingCashFlow(List.of(odd), sources(List.of(odd)));

        assertThat(payout.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(payout.statusReason()).contains("not an outflow");
    }

    private static AnnualFinancials withCash(AnnualFinancials year, String ocf, String dividends, ReportingPeriod period) {
        return new AnnualFinancials(period, year.revenue(), year.netProfit(), year.operatingIncome(),
                year.shareholdersEquity(), year.totalAssets(), year.totalDebt(), year.ebit(), year.currentLiabilities(),
                crore("operatingCashFlow", "fundamentals-timeseries.annualOperatingCashFlow", ocf, period),
                crore("cashDividendsPaid", "fundamentals-timeseries.annualCashDividendsPaid", dividends, period));
    }
}
