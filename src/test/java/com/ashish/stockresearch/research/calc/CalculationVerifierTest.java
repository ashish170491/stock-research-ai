package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants for every calculated metric: the displayed result must be what
 * the formula gives from the recorded inputs, and those inputs must be the
 * source values. Each test tampers with one of the three and expects INVALID.
 */
class CalculationVerifierTest {

    private static final SourceInfo SOURCE = new SourceInfo("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER,
            null, null, null);
    private static final ReportingPeriod FY26 = new ReportingPeriod(com.ashish.stockresearch.research.model.PeriodType.FISCAL_YEAR,
            LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "FY26", 2026, null);
    private static final String REVENUE = "fundamentals-timeseries.annualTotalRevenue";

    private static CalculationInput revenue(String fy, String value) {
        return new CalculationInput(fy + " revenue", REVENUE, new BigDecimal(value), Unit.INR_CRORE, fy);
    }

    private static FinancialDataPoint calculated(Formula formula, String result, Unit unit, CalculationInput... inputs) {
        return FinancialDataPoint.calculated("metric", new BigDecimal(result), unit, FY26, SOURCE, formula,
                "formula text", List.of(inputs));
    }

    private static CalculationVerifier.SourceValues sources() {
        return new CalculationVerifier.SourceValues()
                .add(REVENUE, "FY23", new BigDecimal("120537.50"))
                .add(REVENUE, "FY25", new BigDecimal("183864.11"))
                .add(REVENUE, "FY26", new BigDecimal("192566.78"));
    }

    @Test
    void acceptsACagrThatMatchesItsInputs() {
        // ((192566.78 / 120537.50)^(1/3) - 1) x 100 = 16.90
        FinancialDataPoint cagr = calculated(Formula.CAGR_PERCENT, "16.90", Unit.PERCENT,
                revenue("FY23", "120537.50"), revenue("FY26", "192566.78"),
                CalculationInput.derived("years", new BigDecimal("3"), Unit.YEARS));

        assertThat(CalculationVerifier.verify(cagr, sources()).status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void rejectsADisplayedCagrThatDoesNotFollowFromItsInputs() {
        // The Infosys shape: a CAGR shown next to start and end values it cannot have come from.
        FinancialDataPoint cagr = calculated(Formula.CAGR_PERCENT, "3.44", Unit.PERCENT,
                revenue("FY23", "120537.50"), revenue("FY26", "192566.78"),
                CalculationInput.derived("years", new BigDecimal("3"), Unit.YEARS));

        FinancialDataPoint verified = CalculationVerifier.verify(cagr, sources());

        assertThat(verified.status()).isEqualTo(DataStatus.INVALID);
        assertThat(verified.value()).isNull();
        assertThat(verified.statusReason()).contains("stored result 3.44").contains("16.90");
    }

    @Test
    void rejectsAYoyGrowthWhoseInputsDisagreeWithTheSourceValues() {
        // FY25 recorded as 1,70,000 crore, but the source says 1,83,864.11: the growth rate may be correct
        // arithmetic, but not for the numbers displayed beside it.
        FinancialDataPoint yoy = calculated(Formula.GROWTH_PERCENT, "13.27", Unit.PERCENT,
                revenue("FY25", "170000.00"), revenue("FY26", "192566.78"));

        FinancialDataPoint verified = CalculationVerifier.verify(yoy, sources());

        assertThat(verified.status()).isEqualTo(DataStatus.INVALID);
        assertThat(verified.statusReason()).contains("FY25 revenue").contains("170000.00").contains("183864.11");
    }

    @Test
    void rejectsAValueWhoseDisplayIsNotTheStoredResult() {
        FinancialDataPoint yoy = calculated(Formula.GROWTH_PERCENT, "4.73", Unit.PERCENT,
                revenue("FY25", "183864.11"), revenue("FY26", "192566.78"));
        FinancialDataPoint misdisplayed = new FinancialDataPoint(yoy.metric(), yoy.value(), yoy.unit(), "7.22%",
                null, null, null, yoy.period(), yoy.source(), DataStatus.VALID, null, yoy.calculationStatus(),
                yoy.formula(), yoy.calculation(), yoy.inputs(), yoy.inputFields());

        assertThat(CalculationVerifier.verify(yoy, sources()).status()).isEqualTo(DataStatus.VALID);
        assertThat(CalculationVerifier.problem(misdisplayed, sources())).get().asString()
                .contains("displayed value '7.22%'");
    }

    @ParameterizedTest(name = "{0}: {1} from {2} and {3}")
    @CsvSource({
            // formula, correct result, input a, input b, a wrong result
            "GROWTH_PERCENT, 4.73, 183864.11, 192566.78, 4.83",
            "RATIO_PERCENT, 36.60, 70479.34, 192566.78, 36.70",
            "MULTIPLE, 0.81, 664364.04, 816739.56, 0.91",
            "DRAWDOWN_PERCENT, -31.04, 996.42, 687.13, -30.04",
    })
    void everyFormulaIsRecomputedIndependentlyAndOnlyTheCorrectResultPasses(Formula formula, String result,
                                                                           String a, String b, String wrong) {
        CalculationInput first = CalculationInput.derived("a", new BigDecimal(a), null);
        CalculationInput second = CalculationInput.derived("b", new BigDecimal(b), null);
        Unit unit = formula == Formula.MULTIPLE ? Unit.MULTIPLE : Unit.PERCENT;

        assertThat(CalculationVerifier.problem(calculated(formula, result, unit, first, second), sources())).isEmpty();
        assertThat(CalculationVerifier.problem(calculated(formula, wrong, unit, first, second), sources())).isPresent();
    }

    @Test
    void recomputesReturnsOnAverageAndOnClosingBalances() {
        CalculationInput profit = CalculationInput.derived("FY26 net profit", new BigDecimal("70479.34"), Unit.INR_CRORE);
        CalculationInput closing = CalculationInput.derived("FY26 equity", new BigDecimal("816739.56"), Unit.INR_CRORE);
        CalculationInput opening = CalculationInput.derived("FY25 equity", new BigDecimal("767689.14"), Unit.INR_CRORE);

        assertThat(CalculationVerifier.problem(calculated(Formula.AVERAGE_BALANCE_RETURN_PERCENT, "8.90",
                Unit.PERCENT, profit, closing, opening), sources())).isEmpty();
        assertThat(CalculationVerifier.problem(calculated(Formula.AVERAGE_BALANCE_RETURN_PERCENT, "8.63",
                Unit.PERCENT, profit, closing), sources())).isEmpty();
        // Averaged inputs, closing-only result: rejected.
        assertThat(CalculationVerifier.problem(calculated(Formula.AVERAGE_BALANCE_RETURN_PERCENT, "8.63",
                Unit.PERCENT, profit, closing, opening), sources())).isPresent();
    }

    @Test
    void leavesPointsThatAreNotValidCalculationsAlone() {
        FinancialDataPoint missing = FinancialDataPoint.unavailable("m", Unit.PERCENT, FY26, SOURCE, "not published");

        assertThat(CalculationVerifier.verify(missing, sources())).isSameAs(missing);
    }
}
