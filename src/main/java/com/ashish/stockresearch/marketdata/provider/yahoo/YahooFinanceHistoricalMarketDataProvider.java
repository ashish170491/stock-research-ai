package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.HistoricalMarketDataProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily price history backed by Yahoo's chart endpoint, which is open (no
 * crumb needed) and returns a split- and dividend-adjusted series.
 *
 * This provider returns prices only. Returns, CAGR, drawdown and
 * calendar-year returns are calculated by {@code HistoricalPerformanceService}.
 *
 * Daily bars rather than monthly: a monthly bar is timestamped at the start
 * of the month but carries the month-end close, which mislabels the start
 * and end dates, and month-end sampling understates drawdowns that recover
 * within a month.
 *
 * Prices are dated in the exchange's own timezone: bucketing IST closes by
 * UTC date would push a year-end close into the wrong calendar year.
 */
@Component
@Profile("!mock")
class YahooFinanceHistoricalMarketDataProvider implements HistoricalMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceHistoricalMarketDataProvider.class);

    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    /** Yahoo only accepts a fixed set of range tokens, so a request is widened to the next supported one. */
    private static final List<Integer> SUPPORTED_RANGE_YEARS = List.of(1, 2, 5, 10);

    private final RestClient restClient;
    private final YahooSymbolResolver symbolResolver;

    YahooFinanceHistoricalMarketDataProvider(RestClient yahooFinanceRestClient,
                                             YahooSymbolResolver symbolResolver) {
        this.restClient = yahooFinanceRestClient;
        this.symbolResolver = symbolResolver;
    }

    @Override
    @Cacheable(cacheNames = YahooCacheConfiguration.PRICE_HISTORY, keyGenerator = YahooCacheConfiguration.SYMBOL_KEY)
    public PriceHistory getPriceHistory(String symbolOrName, int years) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));

        YahooChartResponse.Result chart = fetchSeries(resolution.providerSymbol(), years);
        List<PricePoint> series = toSeries(chart);
        if (series.size() < 2) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance returned too little price history for '%s' to measure performance over %d year(s)"
                            .formatted(resolution.providerSymbol(), years));
        }

        log.info("Retrieved Yahoo Finance {}y daily price history for {} ({} points, {} to {})",
                years, resolution.providerSymbol(), series.size(),
                series.get(0).date(), series.get(series.size() - 1).date());

        return new PriceHistory(
                resolution.symbol(),
                resolution.exchange(),
                chart.meta() != null ? chart.meta().currency() : null,
                series,
                "Daily closing prices adjusted for stock splits and dividends (if the market is open, the final "
                        + "point is the latest traded price rather than a close)",
                new DataProvenance(
                        YahooFinanceFinancialDataProvider.SOURCE,
                        SourceType.MARKET_DATA_PROVIDER,
                        DataFreshness.HISTORICAL,
                        Instant.now(),
                        null,
                        series.get(series.size() - 1).date(),
                        null,
                        "Source: Yahoo Finance (unofficial, undocumented endpoints). Historical price series. The end price is the last close in the window, NOT a live "
                                + "quote - use the stock-quote tool for the current price. Returns, CAGR, drawdown "
                                + "and calendar-year returns were calculated by this application; quote them as "
                                + "given and never recalculate them. A null returnPercent means there is no earlier "
                                + "year-end close in the window - report it as not applicable. A yearToDate return "
                                + "covers a partial year and must not be called an annual return."));
    }

    private YahooChartResponse.Result fetchSeries(String providerSymbol, int years) {
        String range = SUPPORTED_RANGE_YEARS.stream()
                .filter(supported -> supported >= years)
                .findFirst()
                .map(supported -> supported + "y")
                .orElse("max");

        YahooChartResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v8/finance/chart/{symbol}")
                            .queryParam("interval", "1d")
                            .queryParam("range", range)
                            .build(providerSymbol))
                    .retrieve()
                    .body(YahooChartResponse.class);
        } catch (RestClientException ex) {
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                    "Could not reach Yahoo Finance for the price history of '%s': %s"
                            .formatted(providerSymbol, ex.getMessage()), ex);
        }

        if (response == null || response.chart() == null || response.chart().error() != null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance returned no price history for '%s'".formatted(providerSymbol));
        }
        List<YahooChartResponse.Result> results = response.chart().result();
        if (results == null || results.isEmpty() || results.get(0) == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance returned no price history for '%s'".formatted(providerSymbol));
        }
        return results.get(0);
    }

    /**
     * Pairs each timestamp with its adjusted close, preferring the
     * split/dividend-adjusted series and falling back to raw closes when
     * Yahoo omits it. Points with a missing price are dropped rather than
     * zero-filled - a zero close would silently register as a -100% return.
     */
    private List<PricePoint> toSeries(YahooChartResponse.Result chart) {
        List<Long> timestamps = chart.timestamp();
        if (timestamps == null || timestamps.isEmpty() || chart.indicators() == null) {
            return List.of();
        }

        List<BigDecimal> prices = adjustedCloses(chart.indicators());
        if (prices == null) {
            return List.of();
        }

        List<PricePoint> series = new ArrayList<>();
        int size = Math.min(timestamps.size(), prices.size());
        for (int i = 0; i < size; i++) {
            BigDecimal price = prices.get(i);
            Long epochSeconds = timestamps.get(i);
            if (price == null || epochSeconds == null || price.signum() <= 0) {
                continue;
            }
            series.add(new PricePoint(Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE).toLocalDate(), price));
        }
        return series;
    }

    private List<BigDecimal> adjustedCloses(YahooChartResponse.Indicators indicators) {
        List<YahooChartResponse.AdjClose> adjusted = indicators.adjclose();
        if (adjusted != null && !adjusted.isEmpty() && adjusted.get(0) != null
                && adjusted.get(0).adjclose() != null) {
            return adjusted.get(0).adjclose();
        }
        List<YahooChartResponse.Quote> quotes = indicators.quote();
        if (quotes != null && !quotes.isEmpty() && quotes.get(0) != null) {
            return quotes.get(0).close();
        }
        return null;
    }
}
