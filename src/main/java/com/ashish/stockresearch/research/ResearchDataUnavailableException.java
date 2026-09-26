package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.ResearchStatus;

/**
 * Thrown by a research provider when data cannot be returned. Carries a
 * {@link ResearchStatus} so the service layer can tell the model the
 * difference between an unknown symbol, a provider outage, and a capability
 * this application simply does not have.
 */
public class ResearchDataUnavailableException extends RuntimeException {

    private final ResearchStatus status;

    public ResearchDataUnavailableException(ResearchStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ResearchDataUnavailableException(ResearchStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public ResearchStatus status() {
        return status;
    }
}
