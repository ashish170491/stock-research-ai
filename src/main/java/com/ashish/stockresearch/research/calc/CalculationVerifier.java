package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks every CALCULATED value before anyone sees it:
 * <ol>
 *   <li>each recorded input matches the source value it claims to come from (so a result cannot be
 *       computed from one number and displayed next to another);</li>
 *   <li>recomputing the formula from those inputs - with arithmetic written independently of
 *       {@link FinancialCalculator} - reproduces the stored result, within the rounding of its last
 *       decimal place;</li>
 *   <li>the displayed text is the stored value.</li>
 * </ol>
 * A value that fails any check is returned as INVALID, with the reason; it is never shown as a result.
 */
public final class CalculationVerifier {

    private static final double DAYS_PER_YEAR = 365.25;

    /** Source values a calculation's inputs are reconciled against, keyed by provider field and period label. */
    public static final class SourceValues {

        private final Map<String, BigDecimal> values = new HashMap<>();

        public SourceValues add(FinancialDataPoint point) {
            if (point != null && point.value() != null && point.source() != null
                    && point.source().sourceField() != null && point.calculationStatus() == CalculationStatus.REPORTED) {
                values.put(key(point.source().sourceField(), point.period().label()), point.value());
            }
            return this;
        }

        public SourceValues add(String field, String periodLabel, BigDecimal value) {
            values.put(key(field, periodLabel), value);
            return this;
        }

        Optional<BigDecimal> find(CalculationInput input) {
            if (input.sourceField() == null || input.period() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(values.get(key(input.sourceField(), input.period())));
        }

        private static String key(String field, String period) {
            return field + "|" + period;
        }
    }

    private CalculationVerifier() {
    }

    /** The point unchanged if it verifies (or is not a VALID calculation), otherwise the same point as INVALID. */
    public static FinancialDataPoint verify(FinancialDataPoint point, SourceValues sources) {
        return problem(point, sources).map(point::invalid).orElse(point);
    }

    /** Why a VALID calculated point does not verify, if it does not. */
    public static Optional<String> problem(FinancialDataPoint point, SourceValues sources) {
        if (point == null || !point.available() || point.calculationStatus() != CalculationStatus.CALCULATED) {
            return Optional.empty();
        }
        for (CalculationInput input : point.inputs()) {
            Optional<BigDecimal> source = sources.find(input);
            if (source.isPresent() && source.get().compareTo(input.value()) != 0) {
                return Optional.of("INVALID: input %s (%s, %s) was recorded as %s but the source value is %s"
                        .formatted(input.name(), input.sourceField(), input.period(), input.value().toPlainString(),
                                source.get().toPlainString()));
            }
        }
        Optional<Double> expected = recompute(point);
        if (expected.isEmpty()) {
            return Optional.of("INVALID: %s cannot be recomputed from its %d recorded input(s) with formula %s"
                    .formatted(point.metric(), point.inputs().size(), point.formula()));
        }
        double tolerance = 0.5 * Math.pow(10, -Math.max(point.value().scale(), 0)) + 1e-9;
        if (Math.abs(point.value().doubleValue() - expected.get()) > tolerance) {
            return Optional.of("INVALID: stored result %s does not match %s recomputed from its inputs (%s)"
                    .formatted(point.value().toPlainString(), BigDecimal.valueOf(expected.get())
                                    .setScale(Math.max(point.value().scale(), 2), java.math.RoundingMode.HALF_UP)
                                    .toPlainString(),
                            String.join("; ", point.inputs().stream().map(CalculationInput::display).toList())));
        }
        String expectedDisplay = FinancialUnits.display(point.value(), point.unit());
        if (!expectedDisplay.equals(point.display())) {
            return Optional.of("INVALID: displayed value '%s' is not the stored result %s"
                    .formatted(point.display(), expectedDisplay));
        }
        return Optional.empty();
    }

    /** The formula evaluated from the recorded inputs, in plain double arithmetic. */
    static Optional<Double> recompute(FinancialDataPoint point) {
        List<Double> in = point.inputs().stream().map(i -> i.value().doubleValue()).toList();
        Double result = switch (point.formula()) {
            case GROWTH_PERCENT -> in.size() == 2 && in.get(0) > 0 ? (in.get(1) / in.get(0) - 1) * 100 : null;
            case CAGR_PERCENT -> in.size() == 3 && in.get(0) > 0 && in.get(1) > 0 && in.get(2) > 0
                    ? (Math.pow(in.get(1) / in.get(0), 1 / in.get(2)) - 1) * 100 : null;
            case RATIO_PERCENT -> in.size() == 2 && in.get(1) > 0 ? in.get(0) / in.get(1) * 100 : null;
            case AVERAGE_BALANCE_RETURN_PERCENT -> {
                if (in.size() == 2 && in.get(1) > 0) {
                    yield in.get(0) / in.get(1) * 100;
                }
                double average = in.size() == 3 ? (in.get(1) + in.get(2)) / 2 : 0;
                yield average > 0 ? in.get(0) / average * 100 : null;
            }
            case MULTIPLE -> in.size() == 2 && in.get(1) > 0 ? in.get(0) / in.get(1) : null;
            case DRAWDOWN_PERCENT -> in.size() == 2 && in.get(0) > 0 ? (in.get(1) / in.get(0) - 1) * 100 : null;
            case DAYS_TO_YEARS -> in.size() == 1 ? in.get(0) / DAYS_PER_YEAR : null;
            case SELECTED_VALUE -> in.size() == 1 ? in.get(0) : null;
        };
        return result == null || !Double.isFinite(result) ? Optional.empty() : Optional.of(result);
    }
}
