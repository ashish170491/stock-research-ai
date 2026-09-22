package com.ashish.stockresearch.marketdata;

import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMarketDataServiceTest {

    @Mock
    private MarketDataProvider marketDataProvider;

    @Test
    void returnsQuoteForAnySymbolTheProviderResolves() {
        StockQuote quote = new StockQuote(
                "WIPRO", "NSE", new BigDecimal("245.30"), "INR", Instant.now(), false, "Yahoo Finance");
        when(marketDataProvider.getQuote("Wipro")).thenReturn(quote);

        StockMarketDataService service = new StockMarketDataService(marketDataProvider);
        StockQuoteResult result = service.getQuote("Wipro");

        assertThat(result.success()).isTrue();
        assertThat(result.quote()).isEqualTo(quote);
    }

    @Test
    void passesThroughCompanyNamesUnchanged() {
        StockQuote quote = new StockQuote(
                "HDFCBANK", "NSE", new BigDecimal("1700.00"), "INR", Instant.now(), false, "Yahoo Finance");
        when(marketDataProvider.getQuote("HDFC Bank")).thenReturn(quote);

        StockMarketDataService service = new StockMarketDataService(marketDataProvider);
        StockQuoteResult result = service.getQuote("HDFC Bank");

        assertThat(result.success()).isTrue();
        assertThat(result.quote().symbol()).isEqualTo("HDFCBANK");
    }

    @Test
    void returnsErrorForBlankInput() {
        StockMarketDataService service = new StockMarketDataService(marketDataProvider);

        StockQuoteResult result = service.getQuote("   ");

        assertThat(result.success()).isFalse();
        assertThat(result.quote()).isNull();
        assertThat(result.errorMessage()).contains("No stock symbol");
    }

    @Test
    void returnsStructuredErrorWhenProviderCannotResolveSymbol() {
        when(marketDataProvider.getQuote("NOTASTOCK"))
                .thenThrow(new MarketDataUnavailableException("no matching NSE or BSE equity found"));

        StockMarketDataService service = new StockMarketDataService(marketDataProvider);
        StockQuoteResult result = service.getQuote("NOTASTOCK");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("unavailable");
    }

    @Test
    void returnsStructuredErrorOnUnexpectedProviderException() {
        when(marketDataProvider.getQuote("INFY"))
                .thenThrow(new RuntimeException("boom"));

        StockMarketDataService service = new StockMarketDataService(marketDataProvider);
        StockQuoteResult result = service.getQuote("INFY");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Unexpected error");
    }
}
