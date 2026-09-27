package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * Metrics calculated deterministically in Java from {@link ReportedFinancials}.
 * The model receives these as finished results and must not recompute them.
 */
public record CalculatedFinancials(
        FinancialDataPoint netProfitMarginTtmPercent,
        FinancialDataPoint latestQuarterRevenueGrowthQoqPercent,
        FinancialDataPoint latestQuarterNetProfitGrowthQoqPercent,
        FinancialDataPoint revenueCagrPercent,
        FinancialDataPoint netProfitCagrPercent,
        List<AnnualRatios> annual
) {
}
