package com.ashish.stockresearch.research.model;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One fiscal year of statement data - the inputs for the year-on-year,
 * CAGR, margin, ROE, ROA and debt-to-equity calculations.
 */
public record AnnualFinancials(
        ReportingPeriod period,
        FinancialDataPoint revenue,
        FinancialDataPoint netProfit,
        FinancialDataPoint operatingIncome,
        FinancialDataPoint shareholdersEquity,
        FinancialDataPoint totalAssets,
        FinancialDataPoint totalDebt
) {

    public List<FinancialDataPoint> points() {
        return List.of(revenue, netProfit, operatingIncome, shareholdersEquity, totalAssets, totalDebt);
    }

    public AnnualFinancials map(UnaryOperator<FinancialDataPoint> f) {
        return new AnnualFinancials(period, f.apply(revenue), f.apply(netProfit), f.apply(operatingIncome),
                f.apply(shareholdersEquity), f.apply(totalAssets), f.apply(totalDebt));
    }
}
