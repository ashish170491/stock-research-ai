package com.ashish.stockresearch.research.model;

/** What span of time a financial figure describes. */
public enum PeriodType {

    /** A single fiscal quarter. */
    QUARTER,

    /** A full fiscal year. */
    FISCAL_YEAR,

    /** The four quarters ending on {@code ReportingPeriod.end}. */
    TRAILING_TWELVE_MONTHS,

    /** A balance or market value at one date (debt, cash, market capitalisation). */
    POINT_IN_TIME,

    /** A price window spanning several years (historical performance). */
    MULTI_YEAR,

    /** The provider did not say which period the figure covers. Never guessed. */
    UNKNOWN
}
