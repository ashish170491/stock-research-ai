package com.ashish.stockresearch.research.model;

/**
 * Structured tool result: the model receives either a populated
 * {@link Shareholding} or an explicit status explaining why not - never a
 * fabricated or zero-filled object.
 */
public record ShareholdingResult(
        boolean success,
        ResearchStatus status,
        Shareholding shareholding,
        String message
) {

    public static ShareholdingResult success(Shareholding shareholding) {
        return new ShareholdingResult(true, ResearchStatus.OK, shareholding, null);
    }

    public static ShareholdingResult failure(ResearchStatus status, String message) {
        return new ShareholdingResult(false, status, null, message);
    }
}
