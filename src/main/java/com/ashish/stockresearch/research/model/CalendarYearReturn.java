package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;

/**
 * One calendar year's price performance within a
 * {@link HistoricalPerformance} series.
 *
 * @param closingPrice  the adjusted close at the end of that year
 * @param returnPercent change versus the previous year's close, or null for the first
 *                      year in the window - there is no prior close to measure it
 *                      against, so the year's return is genuinely not computable here,
 *                      not zero and not to be filled in from elsewhere
 */
public record CalendarYearReturn(
        int year,
        BigDecimal closingPrice,
        BigDecimal returnPercent
) {
}
