package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.model.StockQuote;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The caches run for real: each test proxies its bean through {@link YahooCacheConfiguration}. */
class YahooCacheConfigurationTest {

    /** {@code instance} as the application would see it: behind the caching proxy. */
    static <T> T cached(Class<T> type, T instance) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(YahooCacheConfiguration.class);
        context.registerBean(type, () -> instance);
        context.refresh();
        return context.getBean(type);
    }

    @Test
    void givesEachCacheItsOwnTimeToLive() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(YahooCacheConfiguration.class);
        CacheManager manager = context.getBean(CacheManager.class);

        assertThat(manager.getCacheNames()).containsExactlyInAnyOrder(
                "quotes", "priceHistory", "financials", "companyProfiles", "symbolResolution");
        assertThat(ttl(manager, "quotes")).isEqualTo(Duration.ofMinutes(1));
        assertThat(ttl(manager, "priceHistory")).isEqualTo(Duration.ofHours(6));
        assertThat(ttl(manager, "financials")).isEqualTo(Duration.ofHours(24));
        assertThat(ttl(manager, "companyProfiles")).isEqualTo(Duration.ofHours(24));
        assertThat(ttl(manager, "symbolResolution")).isEqualTo(Duration.ofDays(7));
        assertThat(manager.getCache("misspelt")).isNull();
    }

    private static Duration ttl(CacheManager manager, String name) {
        return ((CaffeineCache) manager.getCache(name)).getNativeCache().policy().expireAfterWrite().orElseThrow()
                .getExpiresAfter();
    }

    @Test
    void servesARepeatedQuoteFromTheCacheWhateverTheCaseOrSpacing() {
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolveWithLatestQuote(any())).thenReturn(Optional.of(new YahooSymbolResolver.Resolution(
                "TCS.NS", "TCS", "NSE", new YahooChartResponse.Meta("INR", "TCS.NS", "NSE", "Tata Consultancy Services", "TCS",
                        new BigDecimal("3500.00"), 1790000000L))));
        YahooFinanceMarketDataProvider provider =
                cached(YahooFinanceMarketDataProvider.class, new YahooFinanceMarketDataProvider(resolver));

        StockQuote first = provider.getQuote("tcs");
        StockQuote second = provider.getQuote(" TCS ");

        assertThat(second).isEqualTo(first);
        verify(resolver, times(1)).resolveWithLatestQuote(any());
    }

    @Test
    void cachesAResolvedNameButNotOneThatResolvedToNothing() {
        YahooSymbolResolver target = mock(YahooSymbolResolver.class);
        when(target.resolve("Infosys")).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution("INFY.NS", "INFY", "NSE", null)));
        when(target.resolve("Not A Company")).thenReturn(Optional.empty());
        YahooSymbolResolver resolver = cached(YahooSymbolResolver.class, target);

        resolver.resolve("Infosys");
        resolver.resolve("INFOSYS");
        resolver.resolve("Not A Company");
        resolver.resolve("Not A Company");

        verify(target, times(1)).resolve("Infosys");
        verify(target, times(2)).resolve("Not A Company");
    }
}
