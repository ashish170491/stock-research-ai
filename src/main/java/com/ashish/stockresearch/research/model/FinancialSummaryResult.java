package com.ashish.stockresearch.research.model;

/**
 * Structured tool result: the model receives either a populated
 * {@link FinancialSummary} or an explicit status explaining why not - never a
 * fabricated or zero-filled object.
 */
public record FinancialSummaryResult(
        boolean success,
        ResearchStatus status,
        FinancialSummary financialSummary,
        String message
) {

    public static FinancialSummaryResult success(FinancialSummary financialSummary) {
        return new FinancialSummaryResult(true, ResearchStatus.OK, financialSummary, null);
    }

    public static FinancialSummaryResult failure(ResearchStatus status, String message) {
        return new FinancialSummaryResult(false, status, null, message);
    }
}
