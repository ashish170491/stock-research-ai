package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.HistoricalMarketDataProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-year price performance backed by Yahoo's chart endpoint, which is
 * open (no crumb needed) and returns a split- and dividend-adjusted series.
 *
 * The returns here are <em>derived by this application</em> from that
 * series, not published by Yahoo. They are arithmetic over real prices, so
 * nothing is invented - but the provenance note says so explicitly, and
 * {@code actualYears} reports the span the series genuinely covers, which
 * is shorter than requested for recently listed companies.
 *
 * Prices are read in the exchange's own timezone: bucketing IST closes by
 * UTC date would push a year-end close into the wrong calendar year.
 */
@Component
@Profile("!mock")
class YahooFinanceHistoricalMarketDataProvider implements HistoricalMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceHistoricalMarketDataProvider.class);

    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MATH = MathContext.DECIMAL64;
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
    public HistoricalPerformance getHistoricalPerformance(String symbolOrName, int years) {
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

        PricePoint first = series.get(0);
        PricePoint last = series.get(series.size() - 1);
        BigDecimal actualYears = yearsBetween(first, last);

        log.info("Retrieved Yahoo Finance {}y price history for {} ({} points, {} to {})",
                years, resolution.providerSymbol(), series.size(), first.date(), last.date());

        return new HistoricalPerformance(
                resolution.symbol(),
                resolution.exchange(),
                chart.meta() != null && chart.meta().currency() != null ? chart.meta().currency() : "INR",
                first.date(),
                last.date(),
                years,
                actualYears,
                first.price(),
                last.price(),
                totalReturnPercent(first.price(), last.price()),
                cagrPercent(first.price(), last.price(), actualYears),
                series.stream().map(PricePoint::price).max(BigDecimal::compareTo).orElse(null),
                series.stream().map(PricePoint::price).min(BigDecimal::compareTo).orElse(null),
                maxDrawdownPercent(series),
                calendarYearReturns(series),
                "Month-end closing prices adjusted for stock splits and dividends",
                new DataProvenance(
                        "Yahoo Finance (unofficial/undocumented endpoint)",
                        DataFreshness.HISTORICAL,
                        Instant.now(),
                        last.date(),
                        "Historical price series. The end price is the last close in this window, "
                                + "NOT a live quote - use the stock-quote tool for the current price. "
                                + "Returns, CAGR and drawdown are calculated by this application from the "
                                + "series above; they are not figures published by the provider. "
                                + "In calendarYearReturns, a null returnPercent means there is no earlier "
                                + "close inside this window to measure that year against - report it as not "
                                + "applicable and never substitute a computed or recalled figure."));
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
                            .queryParam("interval", "1mo")
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

    private record PricePoint(LocalDate date, BigDecimal price) {
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
            series.add(new PricePoint(
                    Instant.ofEpochSecond(epochSeconds).atZone(EXCHANGE_ZONE).toLocalDate(),
                    price));
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

    private BigDecimal yearsBetween(PricePoint first, PricePoint last) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(first.date(), last.date());
        return BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(365.25), MATH)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal totalReturnPercent(BigDecimal start, BigDecimal end) {
        return end.divide(start, MATH)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Uses Math.pow for the fractional root; the inputs are prices, so double precision is ample. */
    private BigDecimal cagrPercent(BigDecimal start, BigDecimal end, BigDecimal years) {
        if (years.signum() <= 0) {
            return null;
        }
        double growth = end.doubleValue() / start.doubleValue();
        double cagr = Math.pow(growth, 1.0 / years.doubleValue()) - 1.0;
        if (!Double.isFinite(cagr)) {
            return null;
        }
        return BigDecimal.valueOf(cagr * 100).setScale(2, RoundingMode.HALF_UP);
    }

    /** Largest peak-to-trough fall in the window, returned as a negative percentage. */
    private BigDecimal maxDrawdownPercent(List<PricePoint> series) {
        BigDecimal peak = null;
        BigDecimal worst = BigDecimal.ZERO;
        for (PricePoint point : series) {
            if (peak == null || point.price().compareTo(peak) > 0) {
                peak = point.price();
            }
            BigDecimal drawdown = totalReturnPercent(peak, point.price());
            if (drawdown.compareTo(worst) < 0) {
                worst = drawdown;
            }
        }
        return worst.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Year-on-year returns using each year's last available close. The
     * earliest year has no prior close in the window, so its return is null
     * rather than a misleading 0%.
     */
    private List<CalendarYearReturn> calendarYearReturns(List<PricePoint> series) {
        Map<Integer, BigDecimal> lastCloseByYear = new LinkedHashMap<>();
        for (PricePoint point : series) {
            lastCloseByYear.put(point.date().getYear(), point.price());
        }

        List<CalendarYearReturn> returns = new ArrayList<>();
        BigDecimal previousClose = null;
        for (Map.Entry<Integer, BigDecimal> entry : lastCloseByYear.entrySet()) {
            BigDecimal close = entry.getValue();
            returns.add(new CalendarYearReturn(
                    entry.getKey(),
                    close.setScale(2, RoundingMode.HALF_UP),
                    previousClose == null ? null : totalReturnPercent(previousClose, close)));
            previousClose = close;
        }
        return List.copyOf(returns);
    }
}
