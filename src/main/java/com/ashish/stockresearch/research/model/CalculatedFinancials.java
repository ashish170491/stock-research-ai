package com.ashish.stockresearch.research.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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

    /** Every calculated point, headline metrics first, then each fiscal year's. */
    public List<FinancialDataPoint> points() {
        List<FinancialDataPoint> points = new ArrayList<>(List.of(netProfitMarginTtmPercent,
                latestQuarterRevenueGrowthQoqPercent, latestQuarterNetProfitGrowthQoqPercent, revenueCagrPercent,
                netProfitCagrPercent));
        annual.forEach(year -> points.addAll(year.points()));
        return List.copyOf(points);
    }

    public CalculatedFinancials map(UnaryOperator<FinancialDataPoint> f) {
        return new CalculatedFinancials(f.apply(netProfitMarginTtmPercent), f.apply(latestQuarterRevenueGrowthQoqPercent),
                f.apply(latestQuarterNetProfitGrowthQoqPercent), f.apply(revenueCagrPercent),
                f.apply(netProfitCagrPercent), annual.stream().map(year -> year.map(f)).toList());
    }
}
