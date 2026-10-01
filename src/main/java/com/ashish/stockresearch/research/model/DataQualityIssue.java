package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * A failed consistency check. Reported prominently; never resolved by
 * picking one of the values.
 *
 * @param affectedFields  provider fields (or calculated metrics) involved. For a
 *                        DATA_CONFLICT, no further conclusion may be drawn from these.
 * @param affectedPeriods the periods (labels such as "FY23") the issue is confined to, when each period's
 *                        figure comes from its own document and is disputed on its own; empty when it
 *                        covers the fields in every period
 */
public record DataQualityIssue(Type type, String message, List<String> affectedFields, List<String> affectedPeriods) {

    public enum Type {
        /** Something looks questionable but no two sources contradict each other. */
        DATA_QUALITY_WARNING,
        /** Two values that should agree do not. Neither is chosen, and neither is used for conclusions. */
        DATA_CONFLICT,
        /** A calculated value did not survive verification against its inputs; it is withheld as INVALID. */
        CALCULATION_INVALID
    }

    public DataQualityIssue {
        affectedFields = List.copyOf(affectedFields);
        affectedPeriods = affectedPeriods == null ? List.of() : List.copyOf(affectedPeriods);
    }

    public DataQualityIssue(Type type, String message, List<String> affectedFields) {
        this(type, message, affectedFields, List.of());
    }

    public static DataQualityIssue conflict(String message, List<String> fields) {
        return new DataQualityIssue(Type.DATA_CONFLICT, message, fields);
    }

    /** A conflict over these fields in these periods only, e.g. one fiscal year's filed net profit. */
    public static DataQualityIssue conflict(String message, List<String> fields, List<String> periods) {
        return new DataQualityIssue(Type.DATA_CONFLICT, message, fields, periods);
    }

    /** Whether the issue covers this field in the period with this label. */
    public boolean covers(String field, String periodLabel) {
        return affectedFields.contains(field) && (affectedPeriods.isEmpty() || affectedPeriods.contains(periodLabel));
    }

    public static DataQualityIssue invalidCalculation(String message, List<String> fields) {
        return new DataQualityIssue(Type.CALCULATION_INVALID, message, fields);
    }

    public static DataQualityIssue warning(String message, List<String> fields) {
        return new DataQualityIssue(Type.DATA_QUALITY_WARNING, message, fields);
    }
}
