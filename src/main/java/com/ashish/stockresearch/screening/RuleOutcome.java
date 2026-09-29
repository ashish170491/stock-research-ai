package com.ashish.stockresearch.screening;

/** A rule's result for one stock. */
public enum RuleOutcome {

    /** The stock meets the criterion, on a VALID and checked value. */
    PASS,

    /** The stock does not meet the criterion, on a VALID and checked value. */
    FAIL,

    /**
     * The criterion cannot be assessed: the value is UNAVAILABLE, in a DATA_CONFLICT, INVALID, not
     * cross-checked, or in a different unit from the rule. Never counted as met.
     */
    INSUFFICIENT_DATA
}
