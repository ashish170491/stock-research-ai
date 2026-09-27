package com.ashish.stockresearch.research.model;

/**
 * What kind of source a number came from, so a report can tell an audited
 * filing from an aggregator's re-publication of it.
 */
public enum SourceType {

    /** Statutory filing (annual report, quarterly results, SEBI shareholding pattern). */
    OFFICIAL_FILING,

    /** The company's own investor-relations material. */
    COMPANY_WEBSITE,

    /** NSE or BSE directly. */
    EXCHANGE,

    /** A third-party aggregator of prices and financial data (e.g. Yahoo Finance). Never an official source. */
    MARKET_DATA_PROVIDER,

    NEWS,

    /** Anything else, including sample data and this application's own calculations. */
    OTHER
}
