package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Minimal shape of Yahoo Finance's unofficial chart API response
 * (https://query1.finance.yahoo.com/v8/finance/chart/{symbol}).
 *
 * The same endpoint serves two purposes here: with {@code range=1d} only the
 * "meta" block matters (current price, used for quotes and to verify a
 * resolved symbol); with a multi-year range the {@code timestamp} and
 * {@code adjclose} arrays carry the price history.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record YahooChartResponse(Chart chart) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<Result> result, ChartError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(Meta meta, List<Long> timestamp, Indicators indicators) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(
            String currency,
            String symbol,
            @JsonProperty("fullExchangeName") String exchangeName,
            @JsonProperty("longName") String longName,
            @JsonProperty("shortName") String shortName,
            @JsonProperty("regularMarketPrice") BigDecimal price,
            @JsonProperty("regularMarketTime") Long regularMarketTimeEpochSeconds
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Indicators(List<AdjClose> adjclose, List<Quote> quote) {
    }

    /** Closing prices adjusted for splits and dividends - the correct basis for return maths. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdjClose(List<BigDecimal> adjclose) {
    }

    /** Raw (unadjusted) OHLC series; only {@code close} is used, as a fallback. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(List<BigDecimal> close) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartError(String code, String description) {
    }
}
