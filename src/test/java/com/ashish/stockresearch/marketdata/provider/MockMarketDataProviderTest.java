package com.ashish.stockresearch.marketdata.provider;

import com.ashish.stockresearch.marketdata.model.StockQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockMarketDataProviderTest {

    private final MockMarketDataProvider provider = new MockMarketDataProvider();

    @Test
    void returnsClearlyMarkedSampleDataForAnyInput() {
        for (String input : new String[] {"RELIANCE", "Wipro", "Some Random Company Ltd"}) {
            StockQuote quote = provider.getQuote(input);

            assertThat(quote.mock()).isTrue();
            assertThat(quote.source()).containsIgnoringCase("mock");
            assertThat(quote.currency()).isEqualTo("INR");
            assertThat(quote.price()).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Test
    void returnsTheSamePriceForTheSameInput() {
        StockQuote first = provider.getQuote("RELIANCE");
        StockQuote second = provider.getQuote("RELIANCE");

        assertThat(first.price()).isEqualByComparingTo(second.price());
    }
}
