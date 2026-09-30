package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.model.StatementScope;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One results filing as NSE lists it: whose accounts, for the period ending when, filed when, and where
 * its XBRL document is. The period's start is not taken from the listing: the document states it.
 *
 * @param periodEnd the last day of the period the filing reports (the fiscal year's end for an annual filing)
 * @param filedAt   when NSE disseminated it; null if the listing does not say
 */
record NseFilingListing(
        String symbol,
        LocalDate periodEnd,
        StatementScope scope,
        boolean audited,
        LocalDateTime filedAt,
        String xbrlUrl,
        Format format
) {

    /** NSE's results format: the older financial-results filing, or SEBI's integrated filing from FY25. */
    enum Format { RESULTS, INTEGRATED }
}
