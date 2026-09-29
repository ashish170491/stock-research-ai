package com.ashish.stockresearch.research.sector;

import java.util.List;
import java.util.Set;

/**
 * Industry groups whose financial statements need different research
 * metrics. Each group states which metrics are primary for it, which generic
 * metrics are not primary indicators of its financial health (and so are
 * never turned into observations or conclusions for it), and which
 * sector-specific metrics a proper analysis would need - so the report can
 * say plainly that those are missing rather than letting generic ratios
 * stand in for them.
 *
 * This is application-supplied domain context, not data about any company,
 * and it supplies no benchmarks: nothing here says what value of any metric
 * is good, bad, high or low.
 */
public enum IndustryGroup {

    BANK(List.of("net profit growth", "return on assets", "return on equity"),
            Set.of("ebitda", "freeCashFlow", "operatingMarginPercent", "debtToEquityPercent", "debtToEquityMultiple",
                    "returnOnCapitalEmployedPercent", "ocfToPat3yMultiple", "ocfToPat5yMultiple",
                    "dividendsToOcfPercent"),
            List.of("EBITDA", "free cash flow", "operating margin", "conventional debt-to-equity", "ROCE",
                    "operating cash flow to net profit", "dividends paid to operating cash flow"),
            List.of("net interest margin", "gross and net NPA", "CASA ratio", "capital adequacy ratio",
                    "credit cost", "loan and deposit growth"),
            "Deposits and borrowings are a bank's raw material, so debt, capital-employed and cash-flow measures "
                    + "do not describe leverage, returns or liquidity as they do for non-financial companies."),

    NBFC(List.of("net profit growth", "return on assets", "return on equity"),
            Set.of("ebitda", "freeCashFlow", "operatingMarginPercent", "returnOnCapitalEmployedPercent",
                    "ocfToPat3yMultiple", "ocfToPat5yMultiple", "dividendsToOcfPercent"),
            List.of("EBITDA", "free cash flow", "operating margin", "ROCE", "operating cash flow to net profit",
                    "dividends paid to operating cash flow"),
            List.of("net interest margin", "gross and net stage-3 assets", "AUM growth", "cost of funds",
                    "capital adequacy ratio"),
            "Borrowing funds its lending book, so debt levels need asset-quality and funding-cost context."),

    INSURANCE(List.of("net profit growth", "return on equity"),
            Set.of("ebitda", "freeCashFlow", "operatingMarginPercent", "debtToEquityPercent", "debtToEquityMultiple",
                    "returnOnCapitalEmployedPercent", "ocfToPat3yMultiple", "ocfToPat5yMultiple",
                    "dividendsToOcfPercent"),
            List.of("EBITDA", "free cash flow", "operating margin", "conventional debt-to-equity", "ROCE",
                    "operating cash flow to net profit", "dividends paid to operating cash flow"),
            List.of("solvency ratio", "value of new business margin", "embedded value", "persistency",
                    "combined ratio"),
            "Insurer revenue and profit follow premium and reserving cycles that generic margins do not capture."),

    /**
     * Financial companies that are not a bank, NBFC or insurer: holding companies, asset managers,
     * exchanges and brokers. Their consolidated statements mix lending, insurance and fee businesses,
     * so industrial measures of leverage, margins and cash flow do not describe them.
     */
    FINANCIAL_OTHER(List.of("net profit growth", "return on equity"),
            Set.of("ebitda", "freeCashFlow", "operatingMarginPercent", "debtToEquityPercent", "debtToEquityMultiple",
                    "returnOnCapitalEmployedPercent", "ocfToPat3yMultiple", "ocfToPat5yMultiple",
                    "dividendsToOcfPercent"),
            List.of("EBITDA", "free cash flow", "operating margin", "conventional debt-to-equity", "ROCE",
                    "operating cash flow to net profit", "dividends paid to operating cash flow"),
            List.of("earnings by subsidiary or segment", "assets under management", "capital adequacy of lending "
                    + "subsidiaries", "solvency of insurance subsidiaries"),
            "Consolidated figures combine lending, insurance and fee businesses, so they need a segment view."),

    IT_SERVICES(List.of("revenue growth", "EBIT / operating margin", "net profit (PAT) growth", "return on equity",
                    "ROCE", "free cash flow", "cash conversion"),
            Set.of(),
            List.of(),
            List.of("constant-currency revenue growth", "deal wins / total contract value", "attrition",
                    "utilisation", "headcount"),
            "Generic growth, margin and return metrics apply; demand and delivery metrics are not in this data."),

    PHARMA(List.of("revenue growth", "operating margin", "net profit growth", "return on equity"),
            Set.of(),
            List.of(),
            List.of("R&D spend", "product pipeline and approvals", "regulatory compliance status",
                    "segment and geography mix"),
            "Regulatory and pipeline factors are not captured by the available financial data."),

    MANUFACTURING(List.of("revenue growth", "operating margin", "return on equity", "debt-to-equity",
                    "free cash flow"),
            Set.of(),
            List.of(),
            List.of("capacity utilisation", "working-capital days", "capital expenditure", "order book"),
            "Capital intensity and working capital are not captured by the available financial data."),

    CONSUMER(List.of("revenue growth", "operating margin", "net profit growth", "return on equity"),
            Set.of(),
            List.of(),
            List.of("volume growth", "gross margin", "same-store sales", "distribution reach"),
            "Volume and pricing split is not captured by the available financial data."),

    OTHER(List.of(), Set.of(), List.of(), List.of(), "No sector-specific framework is applied for this industry."),

    UNKNOWN(List.of(), Set.of(), List.of(), List.of(),
            "The company's sector could not be identified, so no sector-specific interpretation may be applied.");

    private final List<String> primaryMetrics;
    private final Set<String> nonPrimaryMetricKeys;
    private final List<String> lessMeaningfulMetrics;
    private final List<String> sectorMetrics;
    private final String note;

    IndustryGroup(List<String> primaryMetrics, Set<String> nonPrimaryMetricKeys, List<String> lessMeaningfulMetrics,
                  List<String> sectorMetrics, String note) {
        this.primaryMetrics = primaryMetrics;
        this.nonPrimaryMetricKeys = nonPrimaryMetricKeys;
        this.lessMeaningfulMetrics = lessMeaningfulMetrics;
        this.sectorMetrics = sectorMetrics;
        this.note = note;
    }

    /** Metrics that describe this group's financial performance, where the data supplies them. */
    public List<String> primaryMetrics() {
        return primaryMetrics;
    }

    /**
     * True if the application's metric of this name is not a primary indicator of financial health for
     * this group, so no observation or conclusion may be drawn from it without further context.
     */
    public boolean isNonPrimary(String metricKey) {
        return nonPrimaryMetricKeys.contains(metricKey);
    }

    /** The non-primary metrics, in words, for display. */
    public List<String> lessMeaningfulMetrics() {
        return lessMeaningfulMetrics;
    }

    public List<String> sectorMetrics() {
        return sectorMetrics;
    }

    public String note() {
        return note;
    }
}
