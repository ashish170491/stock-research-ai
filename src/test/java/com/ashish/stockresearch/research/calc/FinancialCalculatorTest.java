package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.calc.FinancialCalculator.AnnualReturn;
import com.ashish.stockresearch.research.calc.FinancialCalculator.DatedValue;
import com.ashish.stockresearch.research.calc.FinancialCalculator.Drawdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static DatedValue p(String date, String value) {
        return new DatedValue(LocalDate.parse(date), d(value));
    }

    @Test
    void calculatesCagrFromStartEndAndYears() {
        // Doubling over five years is 2^(1/5) - 1 = 14.87%, not 20% (the simple average).
        assertThat(FinancialCalculator.cagrPercent(d("100"), d("200"), d("5"))).contains(d("14.87"));
        assertThat(FinancialCalculator.cagrPercent(d("100"), d("400"), d("3"))).contains(d("58.74"));
        assertThat(FinancialCalculator.cagrPercent(d("120557"), d("192567"), d("3"))).contains(d("16.90"));
    }

    @Test
    void calculatesANegativeCagrForADecline() {
        assertThat(FinancialCalculator.cagrPercent(d("200"), d("100"), d("2"))).contains(d("-29.29"));
    }

    @Test
    void refusesCagrWhenItIsUndefinedInsteadOfReturningZero() {
        assertThat(FinancialCalculator.cagrPercent(d("0"), d("100"), d("3"))).isEmpty();
        assertThat(FinancialCalculator.cagrPercent(d("-50"), d("100"), d("3"))).isEmpty();
        assertThat(FinancialCalculator.cagrPercent(d("100"), d("200"), d("0"))).isEmpty();
        assertThat(FinancialCalculator.cagrPercent(null, d("200"), d("2"))).isEmpty();
    }

    @Test
    void calculatesYearOnYearGrowth() {
        assertThat(FinancialCalculator.yoyGrowthPercent(d("183864.11"), d("192566.78"))).contains(d("4.73"));
        assertThat(FinancialCalculator.yoyGrowthPercent(d("100"), d("90"))).contains(d("-10.00"));
        assertThat(FinancialCalculator.yoyGrowthPercent(d("0"), d("90"))).isEmpty();
        assertThat(FinancialCalculator.yoyGrowthPercent(null, d("90"))).isEmpty();
    }

    @Test
    void calculatesProfitAndOperatingMargins() {
        assertThat(FinancialCalculator.profitMarginPercent(d("79012.77"), d("294964.43"))).contains(d("26.79"));
        assertThat(FinancialCalculator.operatingMarginPercent(d("25"), d("100"))).contains(d("25.00"));
        assertThat(FinancialCalculator.profitMarginPercent(d("5"), d("0"))).isEmpty();
    }

    @Test
    void calculatesReturnOnEquityOnAverageEquityAndFallsBackToClosing() {
        // 12 / ((100 + 140) / 2) = 10%
        assertThat(FinancialCalculator.returnOnEquityPercent(d("12"), d("100"), d("140"))).contains(d("10.00"));
        // no opening balance: 12 / 140 = 8.57%
        assertThat(FinancialCalculator.returnOnEquityPercent(d("12"), null, d("140"))).contains(d("8.57"));
        assertThat(FinancialCalculator.returnOnEquityPercent(d("12"), d("100"), null)).isEmpty();
    }

    @Test
    void calculatesEarningsYieldAsTheReciprocalOfAPositivePe() {
        // 100 / 26.74 = 3.7397...
        assertThat(FinancialCalculator.earningsYieldPercent(d("26.74"))).contains(d("3.74"));
        // A loss-making company has no meaningful P/E to invert.
        assertThat(FinancialCalculator.earningsYieldPercent(d("0"))).isEmpty();
        assertThat(FinancialCalculator.earningsYieldPercent(d("-12.5"))).isEmpty();
        assertThat(FinancialCalculator.earningsYieldPercent(null)).isEmpty();
    }

    @Test
    void calculatesARatioOfSumsOnlyOverMatchingYears() {
        // (120 + 130 + 150) / (100 + 100 + 100) = 1.33x
        assertThat(FinancialCalculator.sumRatio(List.of(d("120"), d("130"), d("150")),
                List.of(d("100"), d("100"), d("100")))).contains(d("1.33"));
        // Unequal counts, a missing year, or a non-positive denominator: no ratio at all.
        assertThat(FinancialCalculator.sumRatio(List.of(d("120"), d("130")), List.of(d("100")))).isEmpty();
        assertThat(FinancialCalculator.sumRatio(java.util.Arrays.asList(d("120"), null),
                List.of(d("100"), d("100")))).isEmpty();
        assertThat(FinancialCalculator.sumRatio(List.of(d("50")), List.of(d("-10")))).isEmpty();
    }

    @Test
    void calculatesReturnOnCapitalEmployedOnAverageCapitalEmployedAndFallsBackToClosing() {
        // capital employed: 500 - 100 = 400 closing, 360 - 60 = 300 opening; 60 / 350 = 17.14%
        assertThat(FinancialCalculator.returnOnCapitalEmployedPercent(d("60"), d("500"), d("100"), d("360"), d("60")))
                .contains(d("17.14"));
        // no opening balance sheet: 60 / 400 = 15.00%
        assertThat(FinancialCalculator.returnOnCapitalEmployedPercent(d("60"), d("500"), d("100"), null, null))
                .contains(d("15.00"));
        // capital employed that is not positive gives no ROCE
        assertThat(FinancialCalculator.returnOnCapitalEmployedPercent(d("60"), d("100"), d("120"), null, null)).isEmpty();
        assertThat(FinancialCalculator.returnOnCapitalEmployedPercent(d("60"), d("500"), null, null, null)).isEmpty();
    }

    @Test
    void calculatesReturnOnAssets() {
        assertThat(FinancialCalculator.returnOnAssetsPercent(d("70479.34"), d("4818767.11"), d("5262119.32")))
                .contains(d("1.40"));
    }

    @Test
    void measuresDrawdownFromTheRunningPeakAndReportsItsDates() {
        Drawdown drawdown = FinancialCalculator.maxDrawdown(List.of(
                p("2022-01-03", "100"), p("2022-06-01", "200"), p("2023-01-02", "150"),
                p("2023-06-01", "260"), p("2024-01-01", "182"), p("2024-06-03", "400"))).orElseThrow();

        // 260 -> 182 (-30%) is worse than 200 -> 150 (-25%), even though the series ends at a high.
        assertThat(drawdown.percent()).isEqualByComparingTo("-30.00");
        assertThat(drawdown.peakDate()).isEqualTo(LocalDate.of(2023, 6, 1));
        assertThat(drawdown.troughDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void reportsZeroDrawdownForASeriesThatNeverFell() {
        assertThat(FinancialCalculator.maxDrawdown(List.of(p("2024-01-01", "10"), p("2024-02-01", "11"))))
                .hasValueSatisfying(dd -> assertThat(dd.percent()).isEqualByComparingTo("0"));
        assertThat(FinancialCalculator.maxDrawdown(List.of())).isEmpty();
    }

    @Test
    void calculatesCalendarYearReturnsFromYearEndClosesAndFlagsThePartialYear() {
        List<AnnualReturn> returns = FinancialCalculator.calendarYearReturns(List.of(
                p("2023-06-01", "90"), p("2023-12-29", "100"),
                p("2024-06-03", "130"), p("2024-12-31", "120"),
                p("2025-12-31", "150"),
                p("2026-09-25", "165")));

        assertThat(returns).hasSize(4);
        assertThat(returns.get(0).returnPercent()).isNull();
        assertThat(returns.get(1).returnPercent()).isEqualByComparingTo("20.00");
        assertThat(returns.get(1).fromDate()).isEqualTo(LocalDate.of(2023, 12, 29));
        assertThat(returns.get(1).yearToDate()).isFalse();
        assertThat(returns.get(2).returnPercent()).isEqualByComparingTo("25.00");
        AnnualReturn ytd = returns.get(3);
        assertThat(ytd.returnPercent()).isEqualByComparingTo("10.00");
        assertThat(ytd.yearToDate()).isTrue();
        assertThat(ytd.toDate()).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void measuresElapsedYearsFromDates() {
        assertThat(FinancialCalculator.yearsBetween(LocalDate.of(2021, 9, 27), LocalDate.of(2026, 9, 25)))
                .isEqualByComparingTo("4.9938");
    }

    @Test
    void averagesValuesToTwoDecimalPlaces() {
        assertThat(FinancialCalculator.mean(List.of(d("18.20"), d("17.10"), d("16.05")))).contains(d("17.12"));
        assertThat(FinancialCalculator.mean(List.of())).isEmpty();
        assertThat(FinancialCalculator.mean(java.util.Arrays.asList(d("18.20"), null))).isEmpty();
    }

    @Test
    void expressesDividendsPaidAsAShareOfOperatingCashFlow() {
        assertThat(FinancialCalculator.dividendsToOperatingCashFlowPercent(d("-4025.50"), d("6172.00")))
                .contains(d("65.22"));
        assertThat(FinancialCalculator.dividendsToOperatingCashFlowPercent(d("0"), d("6172.00"))).contains(d("0.00"));
        // not an outflow, or no positive cash flow to pay it from: no result rather than a misleading one
        assertThat(FinancialCalculator.dividendsToOperatingCashFlowPercent(d("4025.50"), d("6172.00"))).isEmpty();
        assertThat(FinancialCalculator.dividendsToOperatingCashFlowPercent(d("-4025.50"), d("-100"))).isEmpty();
    }

    @Test
    void sumsFiledLinesAndNeverTreatsAMissingLineAsZero() {
        assertThat(FinancialCalculator.sum(List.of(new BigDecimal("123162"), new BigDecimal("27061"))))
                .contains(new BigDecimal("150223"));
        assertThat(FinancialCalculator.sum(java.util.Arrays.asList(new BigDecimal("123162"), null))).isEmpty();
        assertThat(FinancialCalculator.operatingProfit(new BigDecimal("1075675"), new BigDecimal("981475"),
                new BigDecimal("27061"))).contains(new BigDecimal("121261"));
        assertThat(FinancialCalculator.operatingProfit(new BigDecimal("1075675"), null, new BigDecimal("27061")))
                .isEmpty();
    }
}
