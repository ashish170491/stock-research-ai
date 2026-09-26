package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.FinancialSummary;

/**
 * Supplies headline financial figures. Implementations must leave metrics
 * they cannot source as {@code null} and name them in
 * {@link com.ashish.stockresearch.research.model.FinancialSummary#unavailableMetrics()}
 * rather than substituting a zero or deriving a number the source does not
 * actually support.
 */
public interface FinancialDataProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @throws ResearchDataUnavailableException if no financial data can be retrieved
     */
    FinancialSummary getFinancialSummary(String symbolOrName);
}
