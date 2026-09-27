package com.ashish.stockresearch.research.model;

import java.util.List;
import java.util.function.UnaryOperator;

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

    public List<FinancialDataPoint> points() {
        return List.of(revenue, netProfit, ebitda, freeCashFlow, totalDebt, totalCash, operatingMarginPercent,
                netProfitMarginPercent, returnOnEquityPercent, returnOnAssetsPercent, debtToEquityPercent,
                quarterlyRevenueGrowthYoyPercent, quarterlyEarningsGrowthYoyPercent, returnOnCapitalEmployedPercent);
    }

    public HeadlineFinancials map(UnaryOperator<FinancialDataPoint> f) {
        return new HeadlineFinancials(f.apply(revenue), f.apply(netProfit), f.apply(ebitda), f.apply(freeCashFlow),
                f.apply(totalDebt), f.apply(totalCash), f.apply(operatingMarginPercent), f.apply(netProfitMarginPercent),
                f.apply(returnOnEquityPercent), f.apply(returnOnAssetsPercent), f.apply(debtToEquityPercent),
                f.apply(quarterlyRevenueGrowthYoyPercent), f.apply(quarterlyEarningsGrowthYoyPercent),
                f.apply(returnOnCapitalEmployedPercent));
    }
}
