package com.ashish.stockresearch.research.model;

/**
 * Outcome of a research tool call. Deliberately distinguishes "we cannot do
 * this at all" from "we could, but this lookup failed" so the model can
 * word its answer correctly instead of guessing.
 */
public enum ResearchStatus {

    /** Data retrieved successfully. */
    OK,

    /** The caller passed a blank or unusable symbol. */
    INVALID_SYMBOL,

    /** No NSE/BSE listed company could be matched to the requested symbol or name. */
    SYMBOL_NOT_FOUND,

    /** The company resolved, but the provider holds no data of this kind for it. */
    DATA_NOT_AVAILABLE,

    /**
     * This application has no data source for this capability at all. Not a
     * transient failure and not zero - retrying will not help, and the answer
     * must say the information is unavailable.
     */
    CAPABILITY_NOT_SUPPORTED,

    /** The upstream provider was unreachable, timed out, or rate-limited. */
    PROVIDER_UNAVAILABLE,

    /** The provider replied, but with something we could not parse or trust. */
    PROVIDER_ERROR
}
