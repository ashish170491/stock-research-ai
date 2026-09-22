package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Minimal shape of Yahoo Finance's unofficial symbol search endpoint
 * (https://query1.finance.yahoo.com/v1/finance/search?q=...), used to
 * resolve a free-form stock symbol or company name to a concrete
 * exchange-qualified Yahoo symbol (e.g. "WIPRO.NS").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record YahooSearchResponse(List<Quote> quotes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(
            String symbol,
            String exchange,
            String quoteType,
            String shortname,
            String longname
    ) {
    }
}
