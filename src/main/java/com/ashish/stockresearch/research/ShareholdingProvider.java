package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.Shareholding;

/**
 * Supplies the SEBI quarterly shareholding pattern (promoter / FII / DII /
 * public split) for an Indian listed company.
 *
 * No implementation currently returns real data - see
 * {@code UnsupportedShareholdingProvider}. The interface exists so a real
 * source (NSE or BSE corporate filings) can be dropped in later without
 * touching the tool or service layers.
 */
public interface ShareholdingProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @throws ResearchDataUnavailableException if the shareholding pattern cannot be retrieved
     */
    Shareholding getShareholding(String symbolOrName);
}
