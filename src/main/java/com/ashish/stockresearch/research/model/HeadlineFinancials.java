package com.ashish.stockresearch.research.model;

/**
 * Headline figures as published by the provider, normalised to canonical
 * units. Each point states its own period: flow figures are trailing twelve
 * months, balances are point-in-time at the latest quarter end.
 *
 * The ratio fields here are the provider's own published ratios
 * ({@link CalculationStatus#REPORTED}). Ratios this application calculates from
 * statement data live in {@link CalculatedFinancials}.
 */
public record HeadlineFinancials(
        FinancialDataPoint revenue,
        FinancialDataPoint netProfit,
        FinancialDataPoint ebitda,
        FinancialDataPoint freeCashFlow,
        FinancialDataPoint totalDebt,
        FinancialDataPoint totalCash,
        FinancialDataPoint operatingMarginPercent,
        FinancialDataPoint netProfitMarginPercent,
        FinancialDataPoint returnOnEquityPercent,
        FinancialDataPoint returnOnAssetsPercent,
        FinancialDataPoint debtToEquityPercent,
        FinancialDataPoint quarterlyRevenueGrowthYoyPercent,
        FinancialDataPoint quarterlyEarningsGrowthYoyPercent,
        FinancialDataPoint returnOnCapitalEmployedPercent
) {
}
