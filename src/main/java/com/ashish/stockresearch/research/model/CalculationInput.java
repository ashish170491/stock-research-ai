package com.ashish.stockresearch.research.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * One input to a calculation, recorded with the exact value used so the
 * result can be recomputed and the input checked against its source.
 *
 * @param name        what the input is, e.g. "FY25 revenue"
 * @param sourceField the provider field it came from; null for a derived quantity such as a number of years
 * @param value       the value used, in {@code unit}
 * @param period      the label of the period it covers, as computed by the application
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalculationInput(String name, String sourceField, BigDecimal value, Unit unit, String period) {

    public CalculationInput {
        if (name == null || name.isBlank() || value == null) {
            throw new IllegalArgumentException("a calculation input needs a name and a value");
        }
    }

    /** An input taken from a data point, recording that point's own field, value, unit and period. */
    public static CalculationInput of(String name, FinancialDataPoint point) {
        return new CalculationInput(name, point.source() != null ? point.source().sourceField() : null,
                point.value(), point.unit(), point.period().label());
    }

    /** A derived quantity such as a number of years, which has no provider field of its own. */
    public static CalculationInput derived(String name, BigDecimal value, Unit unit) {
        return new CalculationInput(name, null, value, unit, null);
    }

    public String display() {
        return "%s = %s%s".formatted(name, value.toPlainString(), unit == null ? "" : " " + unit);
    }
}
