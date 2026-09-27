package com.ashish.stockresearch.research.model;

/**
 * Whether a data point may be used. Only {@link #VALID} values are ever
 * used in a calculation, an observation or an interpretation; every other
 * status stops the value at the point where it was detected and is carried
 * to everything that would have depended on it.
 */
public enum DataStatus {

    /** Present, consistent with every check that could be applied, and usable. */
    VALID,

    /** The source did not supply it, or it could not be established. Unknown - never zero, never an estimate. */
    UNAVAILABLE,

    /**
     * Two sources, or two fields of one source, disagree about it. A REPORTED value stays visible so the
     * disagreement can be seen, but neither side is chosen and nothing is calculated or concluded from it.
     */
    DATA_CONFLICT,

    /**
     * A calculated value that failed verification: recomputing it from its recorded inputs, or checking those
     * inputs against the source values, did not reproduce it. It is never shown as a result.
     */
    INVALID
}
