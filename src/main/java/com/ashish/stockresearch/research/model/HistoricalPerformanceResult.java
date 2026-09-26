package com.ashish.stockresearch.research.model;

/**
 * Structured tool result: the model receives either a populated
 * {@link HistoricalPerformance} or an explicit status explaining why not - never a
 * fabricated or zero-filled object.
 */
public record HistoricalPerformanceResult(
        boolean success,
        ResearchStatus status,
        HistoricalPerformance historicalPerformance,
        String message
) {

    public static HistoricalPerformanceResult success(HistoricalPerformance historicalPerformance) {
        return new HistoricalPerformanceResult(true, ResearchStatus.OK, historicalPerformance, null);
    }

    public static HistoricalPerformanceResult failure(ResearchStatus status, String message) {
        return new HistoricalPerformanceResult(false, status, null, message);
    }
}
