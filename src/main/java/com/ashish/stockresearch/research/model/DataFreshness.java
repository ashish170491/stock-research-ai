package com.ashish.stockresearch.research.model;

/**
 * How current a piece of research data is. Carried on every data object so
 * the model can never present a quarterly filing or a five-year-old closing
 * price as if it were a live quote.
 */
public enum DataFreshness {

    /** Live or near-live exchange price. */
    REAL_TIME,

    /** Exchange price on a delay (typically 15 minutes for Indian exchanges via free feeds). */
    DELAYED,

    /** Derived from a periodic company filing - annual report or quarterly result. */
    PERIODIC_FILING,

    /** A past price series; describes what already happened, never the present. */
    HISTORICAL,

    /** Slow-changing descriptive data (sector, business summary) with no meaningful "as of" instant. */
    REFERENCE
}
