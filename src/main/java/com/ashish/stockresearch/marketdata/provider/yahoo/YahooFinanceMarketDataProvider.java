package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.MarketDataProvider;
import com.ashish.stockresearch.marketdata.MarketDataUnavailableException;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Real, near real-time market-data provider backed by Yahoo Finance's
 * unofficial endpoints. No API key required.
 *
 * Symbol resolution lives in {@link YahooSymbolResolver}, which confirms
 * each candidate with a chart lookup and hands back the confirming response
 * - so this provider reads the price straight out of that and makes no
 * extra HTTP call of its own.
 *
 * These endpoints are not officially documented by Yahoo and could change
 * or be rate-limited without notice - that risk is why
 * {@link MarketDataUnavailableException} is used liberally here instead of
 * ever fabricating a price.
 */
@Component
@Profile("!mock")
public class YahooFinanceMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceMarketDataProvider.class);

    private final YahooSymbolResolver symbolResolver;

    public YahooFinanceMarketDataProvider(YahooSymbolResolver symbolResolver) {
        this.symbolResolver = symbolResolver;
    }

    @Override
    public StockQuote getQuote(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new MarketDataUnavailableException(
                        "No NSE or BSE listed stock found matching '" + symbolOrName + "'"));

        YahooChartResponse.Meta meta = resolution.meta();
        Instant timestamp = meta.regularMarketTimeEpochSeconds() != null
                ? Instant.ofEpochSecond(meta.regularMarketTimeEpochSeconds())
                : Instant.now();
        String currency = meta.currency() != null ? meta.currency() : "INR";

        log.info("Retrieved Yahoo Finance quote for {} -> price={}", resolution.providerSymbol(), meta.price());
        return new StockQuote(
                resolution.symbol(),
                resolution.exchange(),
                meta.price(),
                currency,
                timestamp,
                false,
                "Yahoo Finance (near real-time, unofficial/undocumented endpoint)"
        );
    }
}
