package com.ashish.stockresearch.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable market quote for a single Indian stock.
 *
 * @param mock   true when this quote is sample/development data rather than a real market price
 * @param source human-readable description of where the price came from (real provider name or mock label)
 */
public record StockQuote(
        String symbol,
        String exchange,
        BigDecimal price,
        String currency,
        Instant timestamp,
        boolean mock,
        String source
) {
}
