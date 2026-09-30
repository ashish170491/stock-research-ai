package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.calc.FinancialUnits;

import java.math.BigDecimal;

/**
 * One screening criterion: a metric, a comparison and a threshold in the metric's own unit (15 for
 * 15%, 0.5 for 0.5x). A threshold is a heuristic someone configured, not a benchmark.
 */
public record Rule(ScreeningMetric metric, Operator operator, BigDecimal threshold) {

    public Rule {
        if (metric == null || operator == null || threshold == null) {
            throw new IllegalArgumentException("a screening rule needs a metric, an operator and a threshold");
        }
    }

    /** e.g. "ROE 3y avg ≥ 15.00%" */
    public String label() {
        return "%s %s %s".formatted(metric.shortLabel(), operator.symbol(), thresholdDisplay());
    }

    /** e.g. "return on equity, average of the last 3 fiscal years ≥ 15.00%" */
    public String describe() {
        return "%s %s %s".formatted(metric.description(), operator.symbol(), thresholdDisplay());
    }

    public String thresholdDisplay() {
        return FinancialUnits.display(threshold, metric.unit());
    }
}
