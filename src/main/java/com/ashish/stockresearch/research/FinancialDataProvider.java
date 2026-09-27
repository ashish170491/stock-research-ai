package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.ReportedFinancials;

/**
 * Supplies reported financial figures, normalised to canonical units
 * (crore, percent) with a period and source on every number.
 *
 * Implementations report facts only. They must return an UNAVAILABLE data
 * point - never zero and never an estimate - for anything they cannot
 * source, and must not calculate derived metrics: growth, CAGR, margins and
 * returns are calculated from these facts by the service layer.
 */
public interface FinancialDataProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @throws ResearchDataUnavailableException if no financial data can be retrieved
     */
    ReportedFinancials getReportedFinancials(String symbolOrName);
}
