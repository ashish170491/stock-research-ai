package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.ReportedValuation;

/** Where a company's price-based valuation figures come from. */
public interface ValuationDataProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @throws ResearchDataUnavailableException if no valuation data can be retrieved
     */
    ReportedValuation getReportedValuation(String symbolOrName);
}
