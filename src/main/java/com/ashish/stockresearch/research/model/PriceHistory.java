package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * A raw price series as supplied by a {@code HistoricalMarketDataProvider}:
 * facts only. Returns, CAGR and drawdown are calculated from it in Java by
 * the service layer.
 *
 * @param closes date-ordered closes with missing prices already dropped (never zero-filled);
 *               may cover more than the requested window, which the service trims
 * @param basis  what the series is made of, e.g. split- and dividend-adjusted daily closes
 */
public record PriceHistory(
        String symbol,
        String exchange,
        String currency,
        List<PricePoint> closes,
        String basis,
        DataProvenance provenance
) {
}
