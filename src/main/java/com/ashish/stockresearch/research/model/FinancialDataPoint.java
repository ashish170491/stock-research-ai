package com.ashish.stockresearch.research.model;

import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One financial number with everything needed to know what it means, where
 * it came from and whether it may be used - the source of truth the model
 * explains from.
 *
 * <ul>
 *   <li>{@code metric} - what the number is (e.g. "revenue")</li>
 *   <li>{@code rawValue} / {@code providerUnit} - exactly what the provider sent, and what that
 *       raw number was verified to mean</li>
 *   <li>{@code value} / {@code unit} / {@code display} - the normalised value in a canonical unit</li>
 *   <li>{@code currency} - original and normalised currency of a monetary value, and how it was
 *       normalised (scaling only; currencies are never converted)</li>
 *   <li>{@code period}, {@code source} - what it covers and where it came from, including the
 *       provider field</li>
 *   <li>{@code status} - VALID, UNAVAILABLE, DATA_CONFLICT or INVALID, with {@code statusReason}
 *       for anything but VALID</li>
 *   <li>{@code calculationStatus} - REPORTED by the source, or CALCULATED by this application, in
 *       which case {@code formula}, {@code calculation} and {@code inputs} record exactly how, from
 *       which values</li>
 *   <li>{@code inputFields} - the provider fields the value depends on: a calculation's inputs, or
 *       for a REPORTED value, other provider fields the source derived it from</li>
 * </ul>
 *
 * Invariants are enforced at construction:
 * <ul>
 *   <li>VALID: always a value, unit, period and source. REPORTED points always carry the raw
 *       value and its verified provider unit; CALCULATED points always state their formula and
 *       the input values used.</li>
 *   <li>Anything else: always a reason. UNAVAILABLE and INVALID points never carry a value.
 *       A REPORTED point in DATA_CONFLICT keeps the source's value so the disagreement can be
 *       seen; a CALCULATED point in DATA_CONFLICT was never calculated and has no value.
 *       Unknown is not zero and not an estimate.</li>
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
        CurrencyInfo currency,
        ReportingPeriod period,
        SourceInfo source,
        DataStatus status,
        String statusReason,
        CalculationStatus calculationStatus,
        Formula formula,
        String calculation,
        List<CalculationInput> inputs,
        List<String> inputFields
) {

    public static final String SEMANTICS_UNVERIFIED = "metric semantics could not be verified";

    public FinancialDataPoint {
        if (metric == null || metric.isBlank() || status == null || period == null) {
            throw new IllegalArgumentException("metric, status and period are required");
        }
        inputs = inputs == null ? null : List.copyOf(inputs);
        inputFields = inputFields == null ? null : List.copyOf(inputFields);
        if (status == DataStatus.VALID) {
            if (value == null || unit == null || source == null || calculationStatus == null) {
                throw new IllegalArgumentException("a VALID data point needs a value, unit, source and calculation status");
            }
            if (calculationStatus == CalculationStatus.REPORTED && (rawValue == null || providerUnit == null)) {
                throw new IllegalArgumentException("a REPORTED data point must keep its raw value and provider unit");
            }
            if (calculationStatus == CalculationStatus.CALCULATED && (formula == null || calculation == null
                    || calculation.isBlank() || inputs == null || inputs.isEmpty())) {
                throw new IllegalArgumentException("a CALCULATED data point must state its formula and input values");
            }
        } else {
            if (statusReason == null || statusReason.isBlank()) {
                throw new IllegalArgumentException("a " + status + " data point must say why");
            }
            boolean keepsReportedValue = status == DataStatus.DATA_CONFLICT
                    && calculationStatus == CalculationStatus.REPORTED;
            if (value != null && !keepsReportedValue) {
                throw new IllegalArgumentException("a " + status + " data point must not carry a value");
            }
        }
    }

    public static FinancialDataPoint reported(String metric, BigDecimal rawValue, ProviderUnit providerUnit,
                                              BigDecimal value, Unit unit, ReportingPeriod period, SourceInfo source) {
        return new FinancialDataPoint(metric, value, unit, FinancialUnits.display(value, unit), rawValue, providerUnit,
                null, period, source, DataStatus.VALID, null, CalculationStatus.REPORTED, null, null, null, null);
    }

    /**
     * A value calculated by this application. The input fields are taken from the recorded inputs, so
     * what a value depends on can never disagree with what it was calculated from.
     */
    public static FinancialDataPoint calculated(String metric, BigDecimal value, Unit unit, ReportingPeriod period,
                                                SourceInfo inputsSource, Formula formula, String calculation,
                                                List<CalculationInput> inputs) {
        return new FinancialDataPoint(metric, value, unit, FinancialUnits.display(value, unit), null, null, null,
                period, inputsSource, DataStatus.VALID, null, CalculationStatus.CALCULATED, formula, calculation,
                inputs, fieldsOf(inputs));
    }

    public static FinancialDataPoint unavailable(String metric, Unit unit, ReportingPeriod period, SourceInfo source,
                                                 String reason) {
        return new FinancialDataPoint(metric, null, unit, "UNAVAILABLE", null, null, null, period, source,
                DataStatus.UNAVAILABLE, reason, null, null, null, null, null);
    }

    /**
     * A calculation that was not performed because an input is not VALID. The status is the input's
     * (DATA_CONFLICT, INVALID or UNAVAILABLE), the intended formula is kept, and the input fields are
     * recorded so the gap can be traced.
     */
    public static FinancialDataPoint notCalculated(String metric, Unit unit, ReportingPeriod period, SourceInfo source,
                                                   DataStatus status, Formula formula, String calculation,
                                                   List<String> inputFields, String reason) {
        if (status == DataStatus.VALID) {
            throw new IllegalArgumentException("a calculation that was not performed cannot be VALID");
        }
        return new FinancialDataPoint(metric, null, unit, status.name(), null, null, null, period, source, status,
                reason, CalculationStatus.CALCULATED, formula, calculation, null, inputFields);
    }

    /**
     * The provider sent a number, but what it means could not be established.
     * The raw value is kept for traceability only.
     */
    public static FinancialDataPoint semanticsUnverified(String metric, BigDecimal rawValue, ReportingPeriod period,
                                                         SourceInfo source, String detail) {
        return new FinancialDataPoint(metric, null, null, "UNAVAILABLE - " + SEMANTICS_UNVERIFIED, rawValue, null,
                null, period, source, DataStatus.UNAVAILABLE,
                SEMANTICS_UNVERIFIED + (detail == null ? "" : " (" + detail + ")"), null, null, null, null, null);
    }

    public FinancialDataPoint withCurrency(CurrencyInfo currencyInfo) {
        return new FinancialDataPoint(metric, value, unit, display, rawValue, providerUnit, currencyInfo, period, source,
                status, statusReason, calculationStatus, formula, calculation, inputs, inputFields);
    }

    /** Records other provider fields the source derived this REPORTED value from. */
    public FinancialDataPoint derivedFrom(List<String> providerFields) {
        return new FinancialDataPoint(metric, value, unit, display, rawValue, providerUnit, currency, period, source,
                status, statusReason, calculationStatus, formula, calculation, inputs, providerFields);
    }

    /**
     * This value is in a DATA_CONFLICT. A REPORTED value is kept, flagged, so the disagreement stays
     * visible; a CALCULATED value is dropped, because it was computed from something now in dispute.
     */
    public FinancialDataPoint inConflict(String reason) {
        return markedAs(DataStatus.DATA_CONFLICT, reason);
    }

    /** A CALCULATED value that failed verification. The rejected value is named in the reason, never kept. */
    public FinancialDataPoint invalid(String reason) {
        return markedAs(DataStatus.INVALID, reason);
    }

    private FinancialDataPoint markedAs(DataStatus newStatus, String reason) {
        if (status != DataStatus.VALID) {
            return this;
        }
        boolean keepValue = newStatus == DataStatus.DATA_CONFLICT && calculationStatus == CalculationStatus.REPORTED;
        return new FinancialDataPoint(metric, keepValue ? value : null, unit, keepValue ? display : newStatus.name(),
                rawValue, providerUnit, currency, period, source, newStatus, reason, calculationStatus, formula,
                calculation, inputs, inputFields != null ? inputFields : calculationStatus == CalculationStatus.CALCULATED
                        ? fieldsOf(inputs) : null);
    }

    /** Not a record component, so it is not serialised. Only a VALID value may be used for anything. */
    public boolean available() {
        return status == DataStatus.VALID;
    }

    /** Every provider field this value depends on: its calculation inputs, or its own field plus any it was derived from. */
    public List<String> dependsOnFields() {
        if (calculationStatus == CalculationStatus.CALCULATED) {
            return inputFields == null ? List.of() : inputFields;
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (source != null && source.sourceField() != null) {
            fields.add(source.sourceField());
        }
        if (inputFields != null) {
            fields.addAll(inputFields);
        }
        return List.copyOf(fields);
    }

    private static List<String> fieldsOf(List<CalculationInput> inputs) {
        if (inputs == null) {
            return null;
        }
        List<String> fields = new ArrayList<>(new LinkedHashSet<>(inputs.stream()
                .map(CalculationInput::sourceField)
                .filter(field -> field != null && !field.isBlank())
                .toList()));
        return List.copyOf(fields);
    }
}
