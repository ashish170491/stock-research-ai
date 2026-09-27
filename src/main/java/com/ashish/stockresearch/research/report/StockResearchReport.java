package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.sector.SectorContext;

import java.time.LocalDate;
import java.util.List;

/**
 * A complete, structured research report assembled in Java. Every section
 * is either tool data (FACTS), a statement derived deterministically from
 * that data (OBSERVATIONS), or an explicit gap - nothing here is written by
 * the model. The model's interpretation is added separately and labelled as
 * analysis.
 *
 * @param latestReportedQuarter the latest quarter with reported results, as identified from
 *                              provider data; UNKNOWN if the provider did not say
 * @param sectorContext         the company's industry group and which metrics matter for it; UNKNOWN
 *                              when the sector could not be identified
 * @param observations          patterns directly visible in the facts, stated by the application
 * @param withheldObservations  observations not presented because they depend on a field in a
 *                              DATA_CONFLICT
 * @param dataGaps              everything that could not be established, with reasons
 * @param dataQualityIssues     failed cross-checks; conflicting values are shown, flagged, and not
 *                              used for conclusions
 * @param sources               every source used, with its dates
 */
public record StockResearchReport(
        String requestedSymbol,
        String symbol,
        String companyName,
        LocalDate generatedOn,
        ReportingPeriod latestReportedQuarter,
        SectorContext sectorContext,
        CompanyProfileResult companyProfile,
        FinancialSummaryResult financials,
        HistoricalPerformanceResult historicalPerformance,
        ShareholdingResult shareholding,
        List<Observation> observations,
        List<Observation> withheldObservations,
        List<DataGap> dataGaps,
        List<DataQualityIssue> dataQualityIssues,
        List<SourceReference> sources
) {

    /** Every provider field involved in a DATA_CONFLICT - nothing may be concluded from these. */
    public java.util.Set<String> conflictedFields() {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        dataQualityIssues.stream()
                .filter(issue -> issue.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .forEach(issue -> fields.addAll(issue.affectedFields()));
        return fields;
    }
}
