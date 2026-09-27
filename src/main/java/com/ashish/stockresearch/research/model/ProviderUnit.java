package com.ashish.stockresearch.research.model;

/**
 * What a provider's raw number means, as established from the provider's
 * own declaration (for Yahoo Finance, the formatted string it sends next to
 * every raw value) - never inferred from the size of the number.
 */
public enum ProviderUnit {

    /** Whole units of a currency: 2949644288000 = 2.95 trillion. */
    WHOLE_CURRENCY_UNITS,

    /** A ratio expressed as a fraction of one: 0.2679 = 26.79%. */
    FRACTION,

    /** Already in percentage points: 7.27 = 7.27%. */
    PERCENTAGE,

    /** Currency per share (price, EPS). */
    PER_SHARE,

    /** A count of shares. */
    SHARE_COUNT
}
