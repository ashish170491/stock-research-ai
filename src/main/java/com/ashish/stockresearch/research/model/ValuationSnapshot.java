package com.ashish.stockresearch.research.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A company's valuation at one moment, after checking: trailing P/E, price-to-book, dividend yield
 * and earnings yield. Each is a {@link FinancialDataPoint} dated to the price it was measured
 * against, so it is never presented as current once the price has moved. A multiple that does not
 * agree with the price and per-share figures it is quoted against is DATA_CONFLICT; earnings yield
 * is calculated by the application, from a VALID P/E only.
 *
 * <p>These are facts about the price, not judgements: nothing here says whether a multiple is high
 * or low, and there is no fair value, target or recommendation.
 *
 * @param reported         the provider's figures, including the price and per-share inputs the checks used;
 *                         a per-share input whose check failed is DATA_CONFLICT with its multiple
 * @param uncheckedMetrics metrics that are available but could not be cross-checked, with why - usable
 *                         for display, never for passing a screening rule
 */
public record ValuationSnapshot(
        ReportedValuation reported,
        FinancialDataPoint trailingPe,
        FinancialDataPoint priceToBook,
        FinancialDataPoint dividendYieldPercent,
        FinancialDataPoint earningsYieldPercent,
        List<DataQualityIssue> dataQualityIssues,
        List<DataGap> dataGaps,
        Map<String, String> uncheckedMetrics
) {

    public ValuationSnapshot {
        uncheckedMetrics = uncheckedMetrics == null ? Map.of() : Map.copyOf(uncheckedMetrics);
    }

    public LocalDate asOf() {
        return reported.asOf();
    }

    /** The four valuation metrics, in display order. */
    public List<FinancialDataPoint> metrics() {
        return List.of(trailingPe, priceToBook, dividendYieldPercent, earningsYieldPercent);
    }
}
