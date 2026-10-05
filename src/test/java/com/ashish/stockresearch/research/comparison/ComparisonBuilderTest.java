package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.context.ContextFixtures;
import com.ashish.stockresearch.context.OwnHistory;
import com.ashish.stockresearch.context.PeerContext;
import com.ashish.stockresearch.context.ReportContext;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CurrencyInfo;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.ashish.stockresearch.research.ResearchFixtures.CALENDAR;
import static org.assertj.core.api.Assertions.assertThat;

// S5-1, S5-2, S5-6, S5-7
class ComparisonBuilderTest {

    private static final String ROE = "returnOnEquityPercent";
    private static final SourceInfo FILINGS = new SourceInfo("NSE corporate filings (XBRL)", SourceType.OFFICIAL_FILING,
            null, null, null);

    private final MetricGlossary glossary = TestGlossary.glossary();
    private final ComparisonBuilder builder = new ComparisonBuilder(glossary);

    private static FinancialDataPoint roe(int year, String value) {
        ReportingPeriod period = CALENDAR.fiscalYear(LocalDate.of(year, 3, 31));
        BigDecimal v = new BigDecimal(value);
        return FinancialDataPoint.calculated(ROE, v, Unit.PERCENT, period, FILINGS, Formula.SELECTED_VALUE, "test",
                List.of(new CalculationInput("roe", null, v, Unit.PERCENT, period.label())));
    }

    private static FinancialDataPoint conflicted(int year) {
        ReportingPeriod period = CALENDAR.fiscalYear(LocalDate.of(year, 3, 31));
        return FinancialDataPoint.notCalculated(ROE, Unit.PERCENT, period, FILINGS, DataStatus.DATA_CONFLICT,
                Formula.SELECTED_VALUE, "test", List.of(), "sources disagree");
    }

    private static ReportContext oneRatio(String symbol, IndustryGroup group, List<FinancialDataPoint> years) {
        OwnHistory history = OwnHistory.of(ROE, years, true, point -> false).orElseThrow();
        return new ReportContext(symbol, group, null, List.of(new ReportContext.Ratio(ROE, Optional.of(history),
                Optional.empty())), List.of(), null);
    }

    private static ReportContext withPeers(String symbol, IndustryGroup group, List<FinancialDataPoint> years) {
        OwnHistory history = OwnHistory.of(ROE, years, true, point -> false).orElseThrow();
        PeerContext peers = PeerContext.of(ScreeningMetric.ROE_PERCENT, group, symbol, ContextFixtures.RUN,
                ContextFixtures.snapshot(), 5).orElseThrow();
        return new ReportContext(symbol, group, ContextFixtures.RUN, List.of(new ReportContext.Ratio(ROE,
                Optional.of(history), Optional.of(peers))), List.of(), null);
    }

    @Test
    void rejectsFewerThanTwoOrMoreThanFiveCompanies() {
        ReportContext one = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> builder.build(List.of(one), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alignsTwoCompaniesOnTheSameMetricAndPeriodAsComparable() {
        ReportContext a = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));
        ReportContext b = oneRatio("INFY", IndustryGroup.IT_SERVICES, List.of(roe(2025, "25.00"), roe(2026, "27.00")));

        ComparisonTable table = builder.build(List.of(a, b), List.of());

        assertThat(table.symbols()).containsExactly("TCS", "INFY");
        ComparisonTable.Row row = table.rows().stream().filter(r -> r.metricKey().equals(ROE)).findFirst().orElseThrow();
        assertThat(row.cells()).allSatisfy(cell -> assertThat(cell.comparable()).isTrue());
        assertThat(row.cells().get(0).value().display()).isEqualTo("22.00%");
        assertThat(row.cells().get(1).value().display()).isEqualTo("27.00%");
        // S5-6: grouped by the Step 11 question, with the glossary's line on how to read it
        assertThat(row.question()).isEqualTo(glossary.forMetricKey(ROE).orElseThrow().question());
        assertThat(row.howToRead()).isEqualTo(glossary.forMetricKey(ROE).orElseThrow().howToRead());
    }

    @Test
    void marksADifferentPeriodNotComparable() {
        ReportContext a = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));
        // only one year, so its latest and only year is FY25, not FY26
        ReportContext b = oneRatio("WIPRO", IndustryGroup.IT_SERVICES, List.of(roe(2025, "19.00")));

        ComparisonTable table = builder.build(List.of(a, b), List.of());

        ComparisonTable.Row row = table.rows().get(0);
        assertThat(row.cells().get(0).comparable()).isTrue();
        ComparisonTable.Cell mismatched = row.cells().get(1);
        assertThat(mismatched.comparable()).isFalse();
        assertThat(mismatched.notComparableReason()).contains("different period").contains("FY25").contains("FY26");
        assertThat(mismatched.usable()).isFalse();
    }

    @Test
    void marksADifferentCurrencyNotComparable() {
        FinancialDataPoint inUsd = roe(2026, "22.00").withCurrency(CurrencyInfo.unchanged("USD"));
        ReportContext a = oneRatio("TCS", IndustryGroup.IT_SERVICES,
                List.of(roe(2025, "20.00").withCurrency(CurrencyInfo.unchanged("INR")), inUsd));
        ReportContext b = oneRatio("INFY", IndustryGroup.IT_SERVICES,
                List.of(roe(2025, "24.00").withCurrency(CurrencyInfo.unchanged("INR")),
                        roe(2026, "27.00").withCurrency(CurrencyInfo.unchanged("INR"))));

        ComparisonTable table = builder.build(List.of(b, a), List.of());

        ComparisonTable.Row row = table.rows().get(0);
        assertThat(row.cells().get(0).comparable()).isTrue();
        ComparisonTable.Cell usd = row.cells().get(1);
        assertThat(usd.comparable()).isFalse();
        assertThat(usd.notComparableReason()).contains("different currency").contains("USD").contains("INR");
    }

    @Test
    void showsANonValidStatusAndExcludesItFromRanking() {
        ReportContext a = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));
        ReportContext b = oneRatio("INFY", IndustryGroup.IT_SERVICES, List.of(roe(2025, "24.00"), conflicted(2026)));

        ComparisonTable table = builder.build(List.of(a, b), List.of());

        ComparisonTable.Cell conflict = table.rows().get(0).cells().get(1);
        assertThat(conflict.value().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(conflict.usable()).isFalse();
    }

    @Test
    void keepsACompanyThatFailedToLoadAsADataGapInsteadOfDroppingTheComparison() {
        ReportContext a = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));
        ReportContext b = oneRatio("INFY", IndustryGroup.IT_SERVICES, List.of(roe(2025, "24.00"), roe(2026, "27.00")));
        ComparisonTable.Gap gap = new ComparisonTable.Gap("WIPRO", "no quote could be resolved for WIPRO");

        ComparisonTable table = builder.build(List.of(a, b), List.of(gap));

        assertThat(table.gaps()).containsExactly(gap);
        assertThat(table.symbols()).containsExactly("TCS", "INFY");
        assertThat(table.rows()).isNotEmpty();
    }

    @Test
    void showsEachCompanysOwnIndustryGroupMedianBesideItsFigure() {
        ReportContext hdfc = withPeers("HDFCBANK", IndustryGroup.BANK, List.of(roe(2025, "13.50"), roe(2026, "14.03")));
        ReportContext icici = withPeers("ICICIBANK", IndustryGroup.BANK, List.of(roe(2025, "16.80"), roe(2026, "17.50")));

        ComparisonTable table = builder.build(List.of(hdfc, icici), List.of());

        ComparisonTable.Row row = table.rows().get(0);
        assertThat(row.cells()).allSatisfy(cell -> assertThat(cell.groupMedian()).isNotNull());
        assertThat(row.cells().get(0).groupMedian().status()).isEqualTo(DataStatus.VALID);
        String rendered = ComparisonRenderer.render(table);
        assertThat(rendered).contains("Industry group median");
    }

    @Test
    void namesEachCompanysGroupWhenTheyAreNotAllTheSame() {
        ReportContext hdfc = withPeers("HDFCBANK", IndustryGroup.BANK, List.of(roe(2025, "13.50"), roe(2026, "14.03")));
        ReportContext tcs = oneRatio("TCS", IndustryGroup.IT_SERVICES, List.of(roe(2025, "20.00"), roe(2026, "22.00")));

        ComparisonTable table = builder.build(List.of(hdfc, tcs), List.of());

        String rendered = ComparisonRenderer.render(table);
        assertThat(rendered).contains("HDFCBANK (BANK)");
    }
}
