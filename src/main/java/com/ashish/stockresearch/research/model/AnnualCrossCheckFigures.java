package com.ashish.stockresearch.research.model;

/**
 * Fiscal-year revenue and net profit from a second feed of the same
 * provider. Used only to cross-check {@link AnnualFinancials}: when the two
 * feeds disagree about the same year, that is a DATA_CONFLICT. Never used
 * for calculations and never presented as the company's figures.
 */
public record AnnualCrossCheckFigures(
        ReportingPeriod period,
        FinancialDataPoint revenue,
        FinancialDataPoint netProfit
) {
}
