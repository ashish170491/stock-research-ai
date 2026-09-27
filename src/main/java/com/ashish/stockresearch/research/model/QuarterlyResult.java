package com.ashish.stockresearch.research.model;

import java.util.List;
import java.util.function.UnaryOperator;

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

    public List<FinancialDataPoint> points() {
        return List.of(revenue, netProfit, earningsPerShare);
    }

    public QuarterlyResult map(UnaryOperator<FinancialDataPoint> f) {
        return new QuarterlyResult(period, f.apply(revenue), f.apply(netProfit), f.apply(earningsPerShare));
    }
}
