package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * A failed consistency check. Reported prominently; never resolved by
 * picking one of the values.
 *
 * @param affectedFields provider fields (or calculated metrics) involved. For a
 *                       DATA_CONFLICT, no further conclusion may be drawn from these.
 */
public record DataQualityIssue(Type type, String message, List<String> affectedFields) {

    public enum Type {
        /** Something looks questionable but no two sources contradict each other. */
        DATA_QUALITY_WARNING,
        /** Two values that should agree do not. Neither is chosen, and neither is used for conclusions. */
        DATA_CONFLICT
    }

    public DataQualityIssue {
        affectedFields = List.copyOf(affectedFields);
    }

    public static DataQualityIssue conflict(String message, List<String> fields) {
        return new DataQualityIssue(Type.DATA_CONFLICT, message, fields);
    }

    public static DataQualityIssue warning(String message, List<String> fields) {
        return new DataQualityIssue(Type.DATA_QUALITY_WARNING, message, fields);
    }
}
