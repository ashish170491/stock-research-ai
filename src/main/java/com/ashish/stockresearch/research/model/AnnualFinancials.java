package com.ashish.stockresearch.research.model;

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
}
