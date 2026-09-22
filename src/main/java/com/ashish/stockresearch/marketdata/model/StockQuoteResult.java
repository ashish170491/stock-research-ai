package com.ashish.stockresearch.marketdata.model;

/**
 * Structured tool result so the LLM always receives either a quote or a clear
 * explanation of why one isn't available - never a silently fabricated price.
 */
public record StockQuoteResult(
        boolean success,
        StockQuote quote,
        String errorMessage
) {

    public static StockQuoteResult success(StockQuote quote) {
        return new StockQuoteResult(true, quote, null);
    }

    public static StockQuoteResult error(String message) {
        return new StockQuoteResult(false, null, message);
    }
}
