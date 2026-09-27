package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * What the financial-summary tool returns: the provider's reported figures,
 * the metrics calculated from them in Java, and every gap or consistency
 * warning found along the way.
 *
 * All absolute rupee amounts are in {@link Unit#INR_CRORE} with a
 * ready-made {@code display} string; all ratios are {@link Unit#PERCENT}.
 *
 * @param dataGaps            metrics that could not be established, with the reason
 * @param dataQualityIssues   cross-checks that failed. A DATA_CONFLICT names the fields involved;
 *                            both values are still shown, neither is chosen, and no conclusion
 *                            may be drawn from them
 */
public record FinancialSummary(
        ReportedFinancials reported,
        CalculatedFinancials calculated,
        List<DataGap> dataGaps,
        List<DataQualityIssue> dataQualityIssues
) {
}
