package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Headline financial figures for a company.
 *
 * Absolute amounts (revenue, profit, debt, cash, cash flow) are whole units
 * of {@code currency} - a revenue of 2758590070784 INR is roughly 2.76 lakh
 * crore rupees, not 2.76 crore. The currency is whatever the provider reports the
 * company's accounts in. That is NOT always the trading currency of the
 * listing: some Indian companies with US listings are reported in USD
 * upstream, so the currency must always be read rather than assumed to be
 * INR.
 *
 * Every ratio is expressed as a percentage, never as a fraction or a multiple:
 * 23.96 means 23.96%, not 0.2396. That includes
 * {@code debtToEquityPercent} - 10.21 there means debt is 10.21% of equity, a
 * ratio of 0.10x, which is a nearly debt-free balance sheet rather than a
 * heavily geared one.
 *
 * A {@code null} metric means the provider does not publish it. It never
 * means zero. {@link #unavailableMetrics()} names those nulls explicitly so
 * the figure cannot be quietly read as absent-therefore-irrelevant.
 *
 * @param reportingPeriod      plain-language description of what the figures cover
 *                             (e.g. "Trailing twelve months ending 2026-06-30")
 * @param unavailableMetrics   metrics this provider cannot supply, with the reason
 */
public record FinancialSummary(
        String symbol,
        String exchange,
        String companyName,
        String currency,
        String reportingPeriod,
        LocalDate mostRecentQuarterEnd,
        LocalDate lastFiscalYearEnd,
        BigDecimal revenue,
        BigDecimal netProfit,
        BigDecimal ebitda,
        BigDecimal operatingMarginPercent,
        BigDecimal netProfitMarginPercent,
        BigDecimal returnOnEquityPercent,
        BigDecimal returnOnCapitalEmployedPercent,
        BigDecimal returnOnAssetsPercent,
        BigDecimal totalDebt,
        BigDecimal debtToEquityPercent,
        BigDecimal totalCash,
        BigDecimal freeCashFlow,
        BigDecimal revenueGrowthPercent,
        BigDecimal earningsGrowthPercent,
        List<String> unavailableMetrics,
        DataProvenance provenance
) {
}
