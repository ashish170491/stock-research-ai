package com.ashish.stockresearch.research.model;

import java.time.LocalDate;
import java.util.List;

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
 * @param reported the provider's figures, including the price and per-share inputs the checks used
 */
public record ValuationSnapshot(
        ReportedValuation reported,
        FinancialDataPoint trailingPe,
        FinancialDataPoint priceToBook,
        FinancialDataPoint dividendYieldPercent,
        FinancialDataPoint earningsYieldPercent,
        List<DataQualityIssue> dataQualityIssues,
        List<DataGap> dataGaps
) {

    public LocalDate asOf() {
        return reported.asOf();
    }

    /** The four valuation metrics, in display order. */
    public List<FinancialDataPoint> metrics() {
        return List.of(trailingPe, priceToBook, dividendYieldPercent, earningsYieldPercent);
    }
}
