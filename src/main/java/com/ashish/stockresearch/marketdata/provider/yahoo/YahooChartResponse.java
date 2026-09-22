package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Minimal shape of Yahoo Finance's unofficial chart API response
 * (https://query1.finance.yahoo.com/v8/finance/chart/{symbol}). Only the
 * "meta" block is used; the large price/timestamp history arrays are
 * ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record YahooChartResponse(Chart chart) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<Result> result, ChartError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(Meta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(
            String currency,
            String symbol,
            @JsonProperty("fullExchangeName") String exchangeName,
            @JsonProperty("regularMarketPrice") BigDecimal price,
            @JsonProperty("regularMarketTime") Long regularMarketTimeEpochSeconds
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartError(String code, String description) {
    }
}
