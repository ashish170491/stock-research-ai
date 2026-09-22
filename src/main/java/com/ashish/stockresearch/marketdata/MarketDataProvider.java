package com.ashish.stockresearch.marketdata;

import com.ashish.stockresearch.marketdata.model.StockQuote;

/**
 * Abstraction over an external market-data source. Implementations own the
 * translation from a logical symbol or company name to whatever symbol
 * format their upstream provider requires, so the rest of the application
 * never depends on provider-specific symbols.
 */
public interface MarketDataProvider {

    /**
     * @param symbolOrName an Indian stock's trading symbol (e.g. "RELIANCE") or
     *                      company name (e.g. "Reliance Industries")
     * @throws MarketDataUnavailableException if the quote cannot be retrieved
     */
    StockQuote getQuote(String symbolOrName);
}
