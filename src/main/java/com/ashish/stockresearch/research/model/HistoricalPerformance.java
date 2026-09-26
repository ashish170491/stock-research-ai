package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Past price performance of a stock over a requested window.
 *
 * This is HISTORY. {@code endPrice} is the last close in the window, which
 * is not the same thing as a live quote - use the stock-quote tool for the
 * current price.
 *
 * Returns are derived from the price series in {@code basis}; they are
 * computed by this application, not published by the provider.
 *
 * @param basis              what the series is made of, e.g. split- and dividend-adjusted monthly closes
 * @param cagrPercent        compound annual growth rate over {@code actualYears}
 * @param maxDrawdownPercent largest peak-to-trough fall within the window, as a negative percentage
 * @param actualYears        the span actually covered, which can be shorter than requested for
 *                           recently listed companies
 */
public record HistoricalPerformance(
        String symbol,
        String exchange,
        String currency,
        LocalDate periodStart,
        LocalDate periodEnd,
        int requestedYears,
        BigDecimal actualYears,
        BigDecimal startPrice,
        BigDecimal endPrice,
        BigDecimal totalReturnPercent,
        BigDecimal cagrPercent,
        BigDecimal periodHigh,
        BigDecimal periodLow,
        BigDecimal maxDrawdownPercent,
        List<CalendarYearReturn> calendarYearReturns,
        String basis,
        DataProvenance provenance
) {
}
