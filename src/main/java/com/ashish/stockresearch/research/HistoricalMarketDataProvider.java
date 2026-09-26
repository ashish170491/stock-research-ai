package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.HistoricalPerformance;

/**
 * Supplies past price performance over a multi-year window.
 */
public interface HistoricalMarketDataProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @param years        size of the lookback window; implementations may return a shorter
     *                     window for recently listed companies and must say so via
     *                     {@code actualYears}
     * @throws ResearchDataUnavailableException if no price history can be retrieved
     */
    HistoricalPerformance getHistoricalPerformance(String symbolOrName, int years);
}
