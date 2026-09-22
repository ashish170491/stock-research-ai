package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.market-data.yahoo-finance")
public record YahooFinanceProperties(
        String baseUrl,
        String userAgent,
        long timeoutMs
) {
}
