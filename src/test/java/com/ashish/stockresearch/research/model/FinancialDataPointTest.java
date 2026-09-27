package com.ashish.stockresearch.research.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UNKNOWN != ZERO, and every value keeps its provenance - enforced by construction. */
class FinancialDataPointTest {

    private static final SourceInfo SOURCE = new SourceInfo("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER,
            "financialData.totalRevenue", null, null);
    private static final ReportingPeriod PERIOD = ReportingPeriod.pointInTime(LocalDate.of(2026, 6, 30));

    @Test
    void anUnavailablePointCannotCarryAValue() {
        assertThatThrownBy(() -> new FinancialDataPoint("revenue", BigDecimal.ZERO, Unit.INR_CRORE, "0", null, null,
                PERIOD, SOURCE, Availability.UNAVAILABLE, null, null, null, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnavailablePointMustSayWhy() {
        assertThatThrownBy(() -> FinancialDataPoint.unavailable("revenue", Unit.INR_CRORE, PERIOD, SOURCE, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReportedPointMustKeepItsRawValueAndProviderUnit() {
        assertThatThrownBy(() -> FinancialDataPoint.reported("revenue", null, ProviderUnit.WHOLE_CURRENCY_UNITS,
                BigDecimal.ONE, Unit.INR_CRORE, PERIOD, SOURCE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialDataPoint.reported("revenue", BigDecimal.TEN, null,
                BigDecimal.ONE, Unit.INR_CRORE, PERIOD, SOURCE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAvailablePointMustHaveASource() {
        assertThatThrownBy(() -> FinancialDataPoint.reported("revenue", BigDecimal.TEN, ProviderUnit.WHOLE_CURRENCY_UNITS,
                BigDecimal.ONE, Unit.INR_CRORE, PERIOD, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCalculatedPointMustStateItsCalculationAndInputs() {
        assertThatThrownBy(() -> FinancialDataPoint.calculated("m", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                null, List.of("f"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialDataPoint.calculated("m", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                "a / b", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnverifiedMetricKeepsItsRawValueButNeverAValue() {
        FinancialDataPoint point = FinancialDataPoint.semanticsUnverified("debtToEquityPercent", new BigDecimal("7.269"),
                PERIOD, SOURCE, "no unit in the formatted value");

        assertThat(point.available()).isFalse();
        assertThat(point.value()).isNull();
        assertThat(point.unit()).isNull();
        assertThat(point.rawValue()).isEqualByComparingTo("7.269");
        assertThat(point.display()).isEqualTo("UNAVAILABLE - metric semantics could not be verified");
    }

    @Test
    void reportsWhichProviderFieldsAValueDependsOn() {
        FinancialDataPoint reported = FinancialDataPoint.reported("revenue", BigDecimal.TEN,
                ProviderUnit.WHOLE_CURRENCY_UNITS, BigDecimal.ONE, Unit.INR_CRORE, PERIOD, SOURCE);
        FinancialDataPoint calculated = FinancialDataPoint.calculated("margin", BigDecimal.ONE, Unit.PERCENT, PERIOD,
                SOURCE, "a / b", List.of("x", "y"));

        assertThat(reported.dependsOnFields()).containsExactly("financialData.totalRevenue");
        assertThat(calculated.dependsOnFields()).containsExactly("x", "y");
    }

    @Test
    void sourceIsMandatoryAndCannotBeBlank() {
        assertThatThrownBy(() -> new SourceInfo("", SourceType.OTHER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKnownPeriodMustHaveAnEndDate() {
        assertThatThrownBy(() -> new ReportingPeriod(PeriodType.QUARTER, null, null, "Q1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReportingPeriod.unknown().known()).isFalse();
    }
}
