package com.ashish.stockresearch.marketdata.provider;

import com.ashish.stockresearch.marketdata.MarketDataProvider;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Development-only provider. Returns a clearly marked, deterministic sample
 * price for whatever symbol or company name is requested, so the
 * tool-calling flow can be exercised end-to-end without any network access.
 * Only active when the "mock" profile is explicitly enabled - the real
 * {@code YahooFinanceMarketDataProvider} is used otherwise.
 *
 * These prices are NOT real market data.
 */
@Component
@Profile("mock")
public class MockMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(MockMarketDataProvider.class);

    @Override
    public StockQuote getQuote(String symbolOrName) {
        String normalized = symbolOrName.strip().toUpperCase();
        log.warn("Using MOCK market data provider - price for '{}' is SAMPLE DATA, not a real market quote", normalized);

        BigDecimal price = samplePriceFor(normalized);
        return new StockQuote(
                normalized,
                "NSE",
                price,
                "INR",
                Instant.now(),
                true,
                "MOCK-SAMPLE-DATA (not real market data; run without the 'mock' profile for real quotes)"
        );
    }

    /**
     * A stable, made-up price derived from the symbol's hash so repeated
     * calls for the same input return the same sample value.
     */
    private BigDecimal samplePriceFor(String normalized) {
        int magnitude = Math.abs(normalized.hashCode()) % 400000;
        return BigDecimal.valueOf(100 + magnitude / 100.0).setScale(2, RoundingMode.HALF_UP);
    }
}
