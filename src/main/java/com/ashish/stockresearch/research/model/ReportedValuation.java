package com.ashish.stockresearch.research.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Valuation figures as the provider reports them at one moment: the share price and the per-share
 * figures and multiples quoted against it. Nothing here has been checked yet; the valuation
 * service cross-checks the multiples against the price and per-share figures before any is used.
 *
 * @param asOf                 the trading date of the price every figure here is measured against
 * @param trailingEps          trailing-twelve-month earnings per share, in the listing currency
 * @param bookValuePerShare    book value per share, in the listing currency
 * @param dividendPerShare     the indicated annual dividend per share, in the listing currency
 * @param dividendYieldPercent the indicated annual dividend as a percentage of the price
 */
public record ReportedValuation(
        String symbol,
        String exchange,
        String companyName,
        LocalDate asOf,
        FinancialDataPoint price,
        FinancialDataPoint trailingEps,
        FinancialDataPoint bookValuePerShare,
        FinancialDataPoint dividendPerShare,
        FinancialDataPoint trailingPe,
        FinancialDataPoint priceToBook,
        FinancialDataPoint dividendYieldPercent,
        DataProvenance provenance
) {

    public List<FinancialDataPoint> points() {
        return List.of(price, trailingEps, bookValuePerShare, dividendPerShare, trailingPe, priceToBook,
                dividendYieldPercent);
    }
}
