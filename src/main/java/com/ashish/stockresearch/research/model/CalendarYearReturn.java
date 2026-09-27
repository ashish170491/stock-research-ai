package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One calendar year's price performance within a
 * {@link HistoricalPerformance} series, calculated in Java.
 *
 * @param fromDate      the previous year's last close date, or null for the first year
 * @param toDate        this year's last close date in the series
 * @param closingPrice  the adjusted close on {@code toDate}
 * @param returnPercent change versus the previous year's last close, or null for the first year
 *                      in the window - not computable, not zero
 * @param yearToDate    true when the year is incomplete, so the return is partial and must not
 *                      be described as a full-year return
 */
public record CalendarYearReturn(
        int year,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal startClose,
        BigDecimal closingPrice,
        BigDecimal returnPercent,
        boolean yearToDate
) {
}
