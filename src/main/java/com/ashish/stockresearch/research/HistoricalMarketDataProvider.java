package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.PriceHistory;

/**
 * Supplies a raw historical price series. Implementations return prices
 * only; returns, CAGR and drawdown are calculated by the service layer.
 */
public interface HistoricalMarketDataProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol or company name
     * @param years        size of the lookback window; the series may cover more (the service
     *                     trims it) or less for recently listed companies
     * @throws ResearchDataUnavailableException if no price history can be retrieved
     */
    PriceHistory getPriceHistory(String symbolOrName, int years);
}
