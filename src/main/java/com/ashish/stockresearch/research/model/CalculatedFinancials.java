package com.ashish.stockresearch.research.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Metrics calculated deterministically in Java from {@link ReportedFinancials}.
 * The model receives these as finished results and must not recompute them.
 *
 * @param ocfToPat3yMultiple operating cash flow over net profit, summed over the last 3 fiscal years
 * @param ocfToPat5yMultiple the same over 5 fiscal years; UNAVAILABLE unless all 5 are reported
 */
public record CalculatedFinancials(
        FinancialDataPoint netProfitMarginTtmPercent,
        FinancialDataPoint latestQuarterRevenueGrowthQoqPercent,
        FinancialDataPoint latestQuarterNetProfitGrowthQoqPercent,
        FinancialDataPoint revenueCagrPercent,
        FinancialDataPoint netProfitCagrPercent,
        FinancialDataPoint ocfToPat3yMultiple,
        FinancialDataPoint ocfToPat5yMultiple,
        List<AnnualRatios> annual
) {

    /** Every calculated point, headline metrics first, then each fiscal year's. */
    public List<FinancialDataPoint> points() {
        List<FinancialDataPoint> points = new ArrayList<>(List.of(netProfitMarginTtmPercent,
                latestQuarterRevenueGrowthQoqPercent, latestQuarterNetProfitGrowthQoqPercent, revenueCagrPercent,
                netProfitCagrPercent, ocfToPat3yMultiple, ocfToPat5yMultiple));
        annual.forEach(year -> points.addAll(year.points()));
        return List.copyOf(points);
    }

    public CalculatedFinancials map(UnaryOperator<FinancialDataPoint> f) {
        return new CalculatedFinancials(f.apply(netProfitMarginTtmPercent), f.apply(latestQuarterRevenueGrowthQoqPercent),
                f.apply(latestQuarterNetProfitGrowthQoqPercent), f.apply(revenueCagrPercent),
                f.apply(netProfitCagrPercent), f.apply(ocfToPat3yMultiple), f.apply(ocfToPat5yMultiple),
                annual.stream().map(year -> year.map(f)).toList());
    }
}
