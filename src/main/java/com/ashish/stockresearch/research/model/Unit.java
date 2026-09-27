package com.ashish.stockresearch.research.model;

/**
 * Canonical units for every number handed to the model.
 *
 * Provider-specific scales (whole rupees, thousands, millions, billions)
 * never leave the provider: they are converted to one of these in Java
 * before anything is serialised, so the model reads "295000 INR_CRORE" and
 * never has to divide a thirteen-digit rupee figure by ten million itself.
 *
 * Absolute rupee amounts are always {@link #INR_CRORE}. {@link #INR_LAKH_CRORE}
 * exists for display and for tests that pin the lakh-crore conversion; it is
 * not used as a stored unit.
 */
public enum Unit {

    /** Whole rupees, as most providers publish them. Never handed to the model for absolute amounts - see {@link #INR_CRORE}. */
    INR,

    /** 1 crore = 10,000,000 rupees. The canonical unit for absolute rupee amounts. */
    INR_CRORE,

    /** 1 lakh crore = 100,000 crore = 1,000,000,000,000 rupees. */
    INR_LAKH_CRORE,

    /**
     * Millions of US dollars. Only for companies whose accounts the provider
     * reports in USD; never converted to rupees, because no exchange rate is
     * sourced.
     */
    USD_MILLION,

    /** A percentage: 23.96 means 23.96%, never 0.2396 and never 23.96x. */
    PERCENT,

    /** A multiple ("times"): 0.07 means 0.07x. Never a percentage. */
    MULTIPLE,

    /** A count of shares. */
    SHARES,

    /** Rupees per share (share price, EPS). */
    INR_PER_SHARE,

    /** A span of time in years. */
    YEARS
}
