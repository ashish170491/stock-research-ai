package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.FiledFinancials;

/**
 * Supplies a company's annual results as it filed them with the exchange - the source of record, which
 * aggregators such as Yahoo Finance re-key with definitions of their own.
 *
 * Implementations read what the filing states and nothing else: an item the filing does not report is
 * UNAVAILABLE, and no line is derived from others. Whether the filing is internally consistent is
 * checked afterwards, by {@code FiledStatementChecks}, the same way for every implementation. Today's
 * implementation reads NSE's XBRL filings; a licensed data vendor can replace it behind this interface.
 */
public interface FiledFinancialsProvider {

    /**
     * @param symbol       an NSE trading symbol
     * @param fiscalYears  how many of the latest fiscal years to return, at most
     * @throws ResearchDataUnavailableException if no filing can be retrieved at all
     */
    FiledFinancials getFiledFinancials(String symbol, int fiscalYears);
}
