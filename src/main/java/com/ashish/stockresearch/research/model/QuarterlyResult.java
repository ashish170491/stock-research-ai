package com.ashish.stockresearch.research.model;

/**
 * One reported fiscal quarter. The period comes from the provider's
 * period-end date and is labelled by {@code FiscalCalendar}; the
 * publication date is on each point's {@link SourceInfo}.
 */
public record QuarterlyResult(
        ReportingPeriod period,
        FinancialDataPoint revenue,
        FinancialDataPoint netProfit,
        FinancialDataPoint earningsPerShare
) {
}
