package com.ashish.stockresearch.research.model;

import java.util.List;
import java.util.function.UnaryOperator;

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

    public List<FinancialDataPoint> points() {
        return List.of(revenueGrowthYoyPercent, netProfitGrowthYoyPercent, netProfitMarginPercent,
                operatingMarginPercent, returnOnEquityPercent, returnOnAssetsPercent, debtToEquityMultiple);
    }

    public AnnualRatios map(UnaryOperator<FinancialDataPoint> f) {
        return new AnnualRatios(period, f.apply(revenueGrowthYoyPercent), f.apply(netProfitGrowthYoyPercent),
                f.apply(netProfitMarginPercent), f.apply(operatingMarginPercent), f.apply(returnOnEquityPercent),
                f.apply(returnOnAssetsPercent), f.apply(debtToEquityMultiple));
    }
}
