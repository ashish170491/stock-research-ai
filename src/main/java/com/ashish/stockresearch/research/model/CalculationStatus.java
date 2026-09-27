package com.ashish.stockresearch.research.model;

/** Whether a data point's value was published by the source or calculated by this application. */
public enum CalculationStatus {

    /** Published by the source; only unit-normalised here, by a verified conversion rule. */
    REPORTED,

    /** Calculated by this application from REPORTED inputs; the formula and input fields are stated. */
    CALCULATED
}
