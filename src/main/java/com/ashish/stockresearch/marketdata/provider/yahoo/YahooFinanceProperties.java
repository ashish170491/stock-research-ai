package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.market-data.yahoo-finance")
/**
 * @param cookieUrl host used solely to obtain an anonymous Yahoo session cookie, which is
 *                  then exchanged for the "crumb" token that the fundamentals
 *                  ({@code quoteSummary}) endpoints require. It is a different host from
 *                  {@code baseUrl}, hence its own property.
 */
public record YahooFinanceProperties(
        String baseUrl,
        String cookieUrl,
        String userAgent,
        long timeoutMs
) {
}
