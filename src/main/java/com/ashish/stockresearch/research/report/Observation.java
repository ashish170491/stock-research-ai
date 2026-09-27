package com.ashish.stockresearch.research.report;

import java.util.List;

/**
 * A pattern directly visible in the facts, stated in Java from calculated or
 * reported values. Not an explanation - causes belong (if anywhere) in the
 * interpretation, and only when the data establishes them.
 *
 * @param statement   the observation, containing only calculated or reported values
 * @param basis       the calculation it rests on
 * @param inputFields provider fields it depends on - used to withhold it when any of them is in
 *                    a DATA_CONFLICT
 * @param topic       what it is about, e.g. "revenue growth"
 */
public record Observation(String statement, String basis, List<String> inputFields, String topic) {

    public Observation {
        inputFields = List.copyOf(inputFields);
    }
}
