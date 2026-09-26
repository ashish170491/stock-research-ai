package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;

/**
 * Descriptive, slow-changing information about a listed company: what it
 * does and what sector it operates in. Contains no valuation or performance
 * figures - those belong to {@link FinancialSummary}.
 *
 * Any field may be {@code null} when the provider does not publish it. A
 * null means "not available", never zero.
 *
 * @param marketCap         market capitalisation in {@code marketCapCurrency}, or null if not published
 * @param fullTimeEmployees headcount as last reported by the company, or null
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
        BigDecimal marketCap,
        String marketCapCurrency,
        String businessSummary,
        DataProvenance provenance
) {
}
