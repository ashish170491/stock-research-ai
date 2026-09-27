package com.ashish.stockresearch.research.model;

/**
 * Per-fiscal-year metrics calculated by this application from
 * {@link AnnualFinancials}. Every point is {@link CalculationStatus#CALCULATED} and
 * states its formula.
 */
public record AnnualRatios(
        ReportingPeriod period,
        FinancialDataPoint revenueGrowthYoyPercent,
        FinancialDataPoint netProfitGrowthYoyPercent,
        FinancialDataPoint netProfitMarginPercent,
        FinancialDataPoint operatingMarginPercent,
        FinancialDataPoint returnOnEquityPercent,
        FinancialDataPoint returnOnAssetsPercent,
        FinancialDataPoint debtToEquityMultiple
) {
}
