package com.ashish.stockresearch.research.model;

/**
 * Whether a data point carries a value. {@link #UNAVAILABLE} is its own
 * state, not a null that a reader might take for zero.
 */
public enum Availability {
    AVAILABLE,
    UNAVAILABLE
}
