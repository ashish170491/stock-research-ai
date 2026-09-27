package com.ashish.stockresearch.research.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Past price performance over a requested window, calculated in Java from a
 * {@link PriceHistory}.
 *
 * This is HISTORY. {@code endPrice} is the last close in the window, not a
 * live quote.
 *
 * @param window             the exact dates the calculations span
 * @param actualYears        the span actually covered; shorter than requested for recent listings
 * @param maxDrawdownPercent largest peak-to-trough fall, negative; peak and trough dates alongside
 */
public record HistoricalPerformance(
        String symbol,
        String exchange,
        ReportingPeriod window,
        int requestedYears,
        FinancialDataPoint actualYears,
        FinancialDataPoint startPrice,
        FinancialDataPoint endPrice,
        FinancialDataPoint totalReturnPercent,
        FinancialDataPoint cagrPercent,
        FinancialDataPoint periodHigh,
        FinancialDataPoint periodLow,
        FinancialDataPoint maxDrawdownPercent,
        LocalDate drawdownPeakDate,
        LocalDate drawdownTroughDate,
        List<CalendarYearReturn> calendarYearReturns,
        String basis,
        DataProvenance provenance
) {
}
