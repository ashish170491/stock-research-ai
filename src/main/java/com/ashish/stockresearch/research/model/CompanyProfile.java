package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * Descriptive, slow-changing information about a listed company: what it
 * does and what sector it operates in, plus its market capitalisation.
 *
 * Descriptive fields may be {@code null} when the provider does not publish
 * them. Market cap and share count are {@link FinancialDataPoint}s, already
 * normalised to crore and carrying their as-of date.
 *
 * @param businessSummary      the provider's own description - the only source for any
 *                             qualitative claim about what the company does
 * @param dataQualityIssues    failed consistency checks (e.g. market cap vs price x shares)
 */
public record CompanyProfile(
        String symbol,
        String exchange,
        String companyName,
        String sector,
        String industry,
        String country,
        String website,
        Integer fullTimeEmployees,
        FinancialDataPoint marketCap,
        FinancialDataPoint sharesOutstanding,
        String businessSummary,
        List<DataQualityIssue> dataQualityIssues,
        DataProvenance provenance
) {
}
