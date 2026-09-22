package com.ashish.stockresearch.marketdata;

/**
 * Thrown by a {@link MarketDataProvider} when a quote cannot be retrieved:
 * provider outage, timeout, malformed response, or an unsupported symbol on
 * the provider's side.
 */
public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String message) {
        super(message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
