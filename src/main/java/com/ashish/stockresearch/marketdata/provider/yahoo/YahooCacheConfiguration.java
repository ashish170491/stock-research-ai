package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Caches what Yahoo returns, each kind of data for as long as it stays meaningful: a quote for a
 * minute, a valuation for five, a price series for a trading session, statements and profiles for a
 * day, and a name-to-symbol mapping for a week. A repeated question makes no Yahoo calls, and the eventual
 * screener can read many stocks without being rate-limited.
 *
 * Only successful answers are cached: a provider failure is an exception, and exceptions are
 * never cached, so the next request tries Yahoo again.
 */
@Configuration
@Profile("!mock")
// Class-based proxies, as Spring Boot uses by default, so beans stay injectable by their own type.
@EnableCaching(proxyTargetClass = true)
class YahooCacheConfiguration {

    static final String QUOTES = "quotes";
    static final String PRICE_HISTORY = "priceHistory";
    static final String FINANCIALS = "financials";
    static final String COMPANY_PROFILES = "companyProfiles";
    static final String SYMBOL_RESOLUTION = "symbolResolution";
    static final String VALUATIONS = "valuations";

    /** Bean name of the key generator that makes "tcs", " TCS " and "TCS" the same entry. */
    static final String SYMBOL_KEY = "symbolKey";

    static final Map<String, Duration> TIME_TO_LIVE = Map.of(
            QUOTES, Duration.ofMinutes(1),
            PRICE_HISTORY, Duration.ofHours(6),
            FINANCIALS, Duration.ofHours(24),
            COMPANY_PROFILES, Duration.ofHours(24),
            SYMBOL_RESOLUTION, Duration.ofDays(7),
            // Measured against the share price, so kept for minutes, not hours.
            VALUATIONS, Duration.ofMinutes(5));

    private static final int MAX_ENTRIES_PER_CACHE = 2_000;

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // No caches on demand: a mistyped cache name fails instead of silently creating an unbounded
        // cache. Set first, because setCacheNames replaces any cache already registered under a name.
        manager.setCacheNames(Set.of());
        TIME_TO_LIVE.forEach((name, ttl) -> manager.registerCustomCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_ENTRIES_PER_CACHE)
                .build()));
        return manager;
    }

    @Bean(SYMBOL_KEY)
    KeyGenerator symbolKey() {
        return (target, method, params) -> new SimpleKey(Arrays.stream(params)
                .map(param -> param instanceof String text ? text.strip().toUpperCase(Locale.ROOT) : param)
                .toArray());
    }
}
