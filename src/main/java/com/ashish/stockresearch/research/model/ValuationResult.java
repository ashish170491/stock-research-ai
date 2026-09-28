package com.ashish.stockresearch.research.model;

public record ValuationResult(
        boolean success,
        ResearchStatus status,
        ValuationSnapshot valuation,
        String message
) {

    public static ValuationResult success(ValuationSnapshot valuation) {
        return new ValuationResult(true, ResearchStatus.OK, valuation, null);
    }

    public static ValuationResult failure(ResearchStatus status, String message) {
        return new ValuationResult(false, status, null, message);
    }
}
