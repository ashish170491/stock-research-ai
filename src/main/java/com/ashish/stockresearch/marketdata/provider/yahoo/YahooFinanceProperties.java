package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.market-data.yahoo-finance")
/**
 * @param cookieUrl host used solely to obtain an anonymous Yahoo session cookie, which is
 *                  then exchanged for the "crumb" token that the fundamentals
 *                  ({@code quoteSummary}) endpoints require. It is a different host from
 *                  {@code baseUrl}, hence its own property.
 * @param requestsPerSecond the most requests per second sent to Yahoo, across all threads;
 *                  zero or less sends them unthrottled.
 * @param cache     how long each kind of cached response stays fresh before it is fetched again
 */
public record YahooFinanceProperties(
        String baseUrl,
        String cookieUrl,
        String userAgent,
        long timeoutMs,
        double requestsPerSecond,
        Cache cache
) {

    public record Cache(Duration quotes, Duration priceHistory, Duration financials, Duration companyProfiles,
                        Duration symbolResolution, Duration valuations) {
    }
}
