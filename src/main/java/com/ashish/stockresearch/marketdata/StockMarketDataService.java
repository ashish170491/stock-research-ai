package com.ashish.stockresearch.marketdata;

import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business logic for retrieving stock quotes: validates the requested input
 * and turns provider failures into a structured result instead of letting
 * exceptions reach the AI tool layer. Symbol/company-name resolution itself
 * is delegated to the {@link MarketDataProvider}.
 */
@Service
public class StockMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(StockMarketDataService.class);

    private final MarketDataProvider marketDataProvider;

    public StockMarketDataService(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    public StockQuoteResult getQuote(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return StockQuoteResult.error("No stock symbol or company name was provided.");
        }
        String normalized = rawSymbol.strip();

        try {
            StockQuote quote = marketDataProvider.getQuote(normalized);
            return StockQuoteResult.success(quote);
        } catch (MarketDataUnavailableException ex) {
            log.warn("Market data unavailable for '{}': {}", normalized, ex.getMessage());
            return StockQuoteResult.error(
                    "Market data is currently unavailable for '%s': %s".formatted(normalized, ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Unexpected error retrieving market data for '{}'", normalized, ex);
            return StockQuoteResult.error(
                    "Unexpected error retrieving market data for '%s'".formatted(normalized));
        }
    }
}
