package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * Everything a {@code FinancialDataProvider} supplies: reported figures only,
 * already normalised, each with its period and source. No derived metrics -
 * those are calculated from this in Java by the service layer.
 *
 * @param reportingCurrency       currency of the accounts as stated by the provider; not always INR
 * @param latestReportedQuarter   the most recent quarter the provider has results for; UNKNOWN
 *                                when the provider does not say, never inferred from today's date
 * @param latestFiscalYear        the most recent completed fiscal year
 * @param recentQuarters          reported quarters, oldest first; future or unpublished quarters
 *                                are excluded by validation
 * @param annualHistory           fiscal years, oldest first
 * @param annualCrossCheckFigures the same fiscal years' revenue and net profit from a second provider
 *                                feed, used only to detect conflicts with {@code annualHistory}
 * @param dataGaps                what the provider could not supply, and why
 */
public record ReportedFinancials(
        String symbol,
        String exchange,
        String companyName,
        String reportingCurrency,
        ReportingPeriod latestReportedQuarter,
        ReportingPeriod latestFiscalYear,
        HeadlineFinancials headline,
        List<QuarterlyResult> recentQuarters,
        List<AnnualFinancials> annualHistory,
        List<AnnualCrossCheckFigures> annualCrossCheckFigures,
        List<DataGap> dataGaps,
        DataProvenance provenance
) {

    public ReportedFinancials {
        annualCrossCheckFigures = annualCrossCheckFigures == null ? List.of() : List.copyOf(annualCrossCheckFigures);
    }

    public ReportedFinancials withFigures(HeadlineFinancials newHeadline, List<QuarterlyResult> quarters,
                                          List<AnnualFinancials> annual) {
        return new ReportedFinancials(symbol, exchange, companyName, reportingCurrency, latestReportedQuarter,
                latestFiscalYear, newHeadline, quarters, annual, annualCrossCheckFigures, dataGaps, provenance);
    }
}
