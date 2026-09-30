package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.Unit;

/**
 * The metrics a screening rule may test - a fixed list, never a free string, so a rule can only name a
 * metric the snapshot actually records and the application calculates or checks.
 *
 * <p>Fiscal-year spans are exact: Yahoo Finance supplies four fiscal years of statements, so a growth
 * or cash-conversion span is at most three years. A five-year rule would be INSUFFICIENT_DATA for
 * every stock, so none is offered.
 *
 * @param sectorKey the application's metric name, used to check it against each industry group's
 *                  non-primary metrics ({@code IndustryGroup#isNonPrimary})
 */
public enum ScreeningMetric {

    ROE_3Y_AVG_PERCENT("ROE 3y avg", "return on equity, average of the last 3 fiscal years", Unit.PERCENT,
            "returnOnEquityPercent"),
    ROE_PERCENT("ROE", "return on equity, latest fiscal year", Unit.PERCENT, "returnOnEquityPercent"),
    ROA_PERCENT("ROA", "return on assets, latest fiscal year", Unit.PERCENT, "returnOnAssetsPercent"),
    ROCE_PERCENT("ROCE", "return on capital employed, latest fiscal year", Unit.PERCENT,
            "returnOnCapitalEmployedPercent"),
    OPERATING_MARGIN_PERCENT("Operating margin", "operating margin, latest fiscal year", Unit.PERCENT,
            "operatingMarginPercent"),
    DEBT_TO_EQUITY_MULTIPLE("D/E", "debt-to-equity, latest fiscal year end", Unit.MULTIPLE, "debtToEquityMultiple"),
    REVENUE_CAGR_3Y_PERCENT("Revenue CAGR 3y", "revenue CAGR over the last 3 fiscal years", Unit.PERCENT,
            "revenueCagrPercent"),
    NET_PROFIT_CAGR_3Y_PERCENT("Profit CAGR 3y", "net profit CAGR over the last 3 fiscal years", Unit.PERCENT,
            "netProfitCagrPercent"),
    OCF_TO_PAT_3Y_MULTIPLE("OCF/PAT 3y", "operating cash flow to net profit over the last 3 fiscal years",
            Unit.MULTIPLE, "ocfToPat3yMultiple"),
    DIVIDENDS_TO_OCF_PERCENT("Dividends/OCF", "dividends paid as a share of operating cash flow, latest fiscal year",
            Unit.PERCENT, "dividendsToOcfPercent"),
    TRAILING_PE_MULTIPLE("P/E", "trailing P/E at the snapshot's share price", Unit.MULTIPLE, "trailingPe"),
    PRICE_TO_BOOK_MULTIPLE("P/B", "price-to-book at the snapshot's share price", Unit.MULTIPLE, "priceToBook"),
    EARNINGS_YIELD_PERCENT("Earnings yield", "earnings yield (100 / trailing P/E) at the snapshot's share price",
            Unit.PERCENT, "earningsYieldPercent"),
    DIVIDEND_YIELD_PERCENT("Dividend yield", "dividend yield at the snapshot's share price", Unit.PERCENT,
            "dividendYieldPercent");

    private final String shortLabel;
    private final String description;
    private final Unit unit;
    private final String sectorKey;

    ScreeningMetric(String shortLabel, String description, Unit unit, String sectorKey) {
        this.shortLabel = shortLabel;
        this.description = description;
        this.unit = unit;
        this.sectorKey = sectorKey;
    }

    public String shortLabel() {
        return shortLabel;
    }

    public String description() {
        return description;
    }

    public Unit unit() {
        return unit;
    }

    public String sectorKey() {
        return sectorKey;
    }
}
