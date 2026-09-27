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
                null, PERIOD, SOURCE, DataStatus.UNAVAILABLE, "missing", null, null, null, null, null))
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
    void aCalculatedPointMustStateItsFormulaAndInputValues() {
        List<CalculationInput> inputs = List.of(new CalculationInput("a", "x", BigDecimal.ONE, Unit.INR_CRORE, "FY26"));
        assertThatThrownBy(() -> FinancialDataPoint.calculated("m", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                Formula.RATIO_PERCENT, null, inputs)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialDataPoint.calculated("m", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                null, "a / b", inputs)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialDataPoint.calculated("m", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                Formula.RATIO_PERCENT, "a / b", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReportedValueInConflictStaysVisibleButIsNotUsable() {
        FinancialDataPoint revenue = FinancialDataPoint.reported("revenue", BigDecimal.TEN,
                ProviderUnit.WHOLE_CURRENCY_UNITS, BigDecimal.ONE, Unit.INR_CRORE, PERIOD, SOURCE);

        FinancialDataPoint conflicted = revenue.inConflict("DATA_CONFLICT: two fields disagree");

        assertThat(conflicted.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(conflicted.available()).isFalse();
        assertThat(conflicted.value()).isEqualByComparingTo("1");
        assertThat(conflicted.statusReason()).contains("two fields disagree");
    }

    @Test
    void aCalculatedValueInConflictOrInvalidHasNoValue() {
        FinancialDataPoint margin = FinancialDataPoint.calculated("margin", BigDecimal.ONE, Unit.PERCENT, PERIOD, SOURCE,
                Formula.RATIO_PERCENT, "a / b x 100", List.of(
                        new CalculationInput("a", "x", BigDecimal.ONE, Unit.INR_CRORE, "FY26"),
                        new CalculationInput("b", "y", new BigDecimal("100"), Unit.INR_CRORE, "FY26")));

        FinancialDataPoint conflicted = margin.inConflict("input in conflict");
        FinancialDataPoint invalid = margin.invalid("did not reproduce");

        assertThat(conflicted.value()).isNull();
        assertThat(conflicted.display()).isEqualTo("DATA_CONFLICT");
        assertThat(conflicted.dependsOnFields()).containsExactly("x", "y");
        assertThat(invalid.value()).isNull();
        assertThat(invalid.status()).isEqualTo(DataStatus.INVALID);
        assertThat(invalid.inputs()).hasSize(2);
    }

    @Test
    void aMetricThatWasNotCalculatedCannotClaimToBeValid() {
        assertThatThrownBy(() -> FinancialDataPoint.notCalculated("m", Unit.PERCENT, PERIOD, SOURCE, DataStatus.VALID,
                Formula.RATIO_PERCENT, "a / b", List.of("x"), "reason")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currenciesAreNeverConverted() {
        assertThatThrownBy(() -> new CurrencyInfo("USD", "INR", CurrencyInfo.Conversion.SCALED_SAME_CURRENCY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CurrencyInfo.scaled("inr").normalizedCurrency()).isEqualTo("INR");
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
                SOURCE, Formula.RATIO_PERCENT, "a / b", List.of(
                        new CalculationInput("a", "x", BigDecimal.ONE, Unit.INR_CRORE, "FY26"),
                        new CalculationInput("b", "y", BigDecimal.TEN, Unit.INR_CRORE, "FY26"),
                        CalculationInput.derived("years", BigDecimal.ONE, Unit.YEARS)));
        FinancialDataPoint derived = FinancialDataPoint.reported("profitMargins", BigDecimal.ONE,
                ProviderUnit.FRACTION, BigDecimal.TEN, Unit.PERCENT, PERIOD, SOURCE.withField("financialData.profitMargins"))
                .derivedFrom(List.of("financialData.totalRevenue"));

        assertThat(reported.dependsOnFields()).containsExactly("financialData.totalRevenue");
        assertThat(calculated.dependsOnFields()).containsExactly("x", "y");
        assertThat(derived.dependsOnFields()).containsExactly("financialData.profitMargins", "financialData.totalRevenue");
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
