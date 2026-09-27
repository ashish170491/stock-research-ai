package com.ashish.stockresearch.research.model;

import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * One financial number with everything needed to know what it means and
 * where it came from - the source of truth the model explains from.
 *
 * <ul>
 *   <li>{@code metric} - what the number is (e.g. "revenue")</li>
 *   <li>{@code rawValue} / {@code providerUnit} - exactly what the provider sent, and what that
 *       raw number was verified to mean</li>
 *   <li>{@code value} / {@code unit} / {@code display} - the normalised value in a canonical unit</li>
 *   <li>{@code period}, {@code source} - what it covers and where it came from, including the
 *       provider field</li>
 *   <li>{@code calculationStatus}, {@code calculation}, {@code inputFields} - REPORTED, or
 *       CALCULATED with its formula and the fields it was calculated from</li>
 * </ul>
 *
 * Invariants are enforced at construction:
 * <ul>
 *   <li>AVAILABLE: always a value, unit, period and source. REPORTED points always carry the raw
 *       value and its verified provider unit; CALCULATED points always state their formula and
 *       inputs.</li>
 *   <li>UNAVAILABLE: never a normalised value, always a reason. The raw value may be kept for
 *       traceability (e.g. when its meaning could not be verified), but it is never presented as
 *       the metric's value. Unknown is not zero and not an estimate.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialDataPoint(
        String metric,
        BigDecimal value,
        Unit unit,
        String display,
        BigDecimal rawValue,
        ProviderUnit providerUnit,
        ReportingPeriod period,
        SourceInfo source,
        Availability availability,
        CalculationStatus calculationStatus,
        String calculation,
        List<String> inputFields,
        String unavailableReason
) {

    public static final String SEMANTICS_UNVERIFIED = "metric semantics could not be verified";

    public FinancialDataPoint {
        if (metric == null || metric.isBlank() || availability == null || period == null) {
            throw new IllegalArgumentException("metric, availability and period are required");
        }
        inputFields = inputFields == null ? null : List.copyOf(inputFields);
        if (availability == Availability.AVAILABLE) {
            if (value == null || unit == null || source == null || calculationStatus == null) {
                throw new IllegalArgumentException("an AVAILABLE data point needs a value, unit, source and status");
            }
            if (calculationStatus == CalculationStatus.REPORTED && (rawValue == null || providerUnit == null)) {
                throw new IllegalArgumentException("a REPORTED data point must keep its raw value and provider unit");
            }
            if (calculationStatus == CalculationStatus.CALCULATED
                    && (calculation == null || calculation.isBlank() || inputFields == null || inputFields.isEmpty())) {
                throw new IllegalArgumentException("a CALCULATED data point must state its calculation and inputs");
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException("an UNAVAILABLE data point must not carry a value");
            }
            if (unavailableReason == null || unavailableReason.isBlank()) {
                throw new IllegalArgumentException("an UNAVAILABLE data point must say why");
            }
        }
    }

    public static FinancialDataPoint reported(String metric, BigDecimal rawValue, ProviderUnit providerUnit,
                                              BigDecimal value, Unit unit, ReportingPeriod period, SourceInfo source) {
        return new FinancialDataPoint(metric, value, unit, FinancialUnits.display(value, unit), rawValue, providerUnit,
                period, source, Availability.AVAILABLE, CalculationStatus.REPORTED, null, null, null);
    }

    public static FinancialDataPoint calculated(String metric, BigDecimal value, Unit unit, ReportingPeriod period,
                                                SourceInfo inputsSource, String calculation, List<String> inputFields) {
        return new FinancialDataPoint(metric, value, unit, FinancialUnits.display(value, unit), null, null, period,
                inputsSource, Availability.AVAILABLE, CalculationStatus.CALCULATED, calculation, inputFields, null);
    }

    public static FinancialDataPoint unavailable(String metric, Unit unit, ReportingPeriod period, SourceInfo source,
                                                 String reason) {
        return new FinancialDataPoint(metric, null, unit, "UNAVAILABLE", null, null, period, source,
                Availability.UNAVAILABLE, null, null, null, reason);
    }

    /**
     * The provider sent a number, but what it means could not be established.
     * The raw value is kept for traceability only.
     */
    public static FinancialDataPoint semanticsUnverified(String metric, BigDecimal rawValue, ReportingPeriod period,
                                                         SourceInfo source, String detail) {
        return new FinancialDataPoint(metric, null, null, "UNAVAILABLE - " + SEMANTICS_UNVERIFIED, rawValue, null,
                period, source, Availability.UNAVAILABLE, null, null, null,
                SEMANTICS_UNVERIFIED + (detail == null ? "" : " (" + detail + ")"));
    }

    /** Not a record component, so it is not serialised; the model reads {@code availability}. */
    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    /** Every provider field this value depends on: its own source field, or its calculation inputs. */
    public List<String> dependsOnFields() {
        if (inputFields != null) {
            return inputFields;
        }
        return source != null && source.sourceField() != null ? List.of(source.sourceField()) : List.of();
    }
}
