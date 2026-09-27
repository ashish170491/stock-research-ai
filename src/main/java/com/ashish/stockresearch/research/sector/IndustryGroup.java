package com.ashish.stockresearch.research.sector;

import java.util.List;

/**
 * Industry groups whose financial statements need different research
 * metrics. Each group states which generic metrics are less meaningful for
 * it and which sector-specific metrics a proper analysis would need - so the
 * report can say plainly that those are missing rather than letting generic
 * ratios stand in for them.
 *
 * This is application-supplied domain context, not data about any company.
 */
public enum IndustryGroup {

    BANK(List.of("EBITDA", "operating margin", "debt-to-equity", "free cash flow"),
            List.of("net interest margin", "gross and net NPA", "CASA ratio", "capital adequacy ratio",
                    "credit cost", "loan and deposit growth"),
            "Deposits and borrowings are a bank's raw material, so debt and cash-flow measures do not describe "
                    + "leverage or liquidity as they do for non-financial companies."),

    NBFC(List.of("EBITDA", "operating margin", "free cash flow"),
            List.of("net interest margin", "gross and net stage-3 assets", "AUM growth", "cost of funds",
                    "capital adequacy ratio"),
            "Borrowing funds its lending book, so debt levels need asset-quality and funding-cost context."),

    INSURANCE(List.of("EBITDA", "operating margin", "debt-to-equity", "free cash flow"),
            List.of("solvency ratio", "value of new business margin", "embedded value", "persistency",
                    "combined ratio"),
            "Insurer revenue and profit follow premium and reserving cycles that generic margins do not capture."),

    IT_SERVICES(List.of(),
            List.of("constant-currency revenue growth", "deal wins / total contract value", "attrition",
                    "utilisation", "headcount"),
            "Generic growth, margin and return metrics apply; demand and delivery metrics are not in this data."),

    PHARMA(List.of(),
            List.of("R&D spend", "product pipeline and approvals", "regulatory compliance status",
                    "segment and geography mix"),
            "Regulatory and pipeline factors are not captured by the available financial data."),

    MANUFACTURING(List.of(),
            List.of("capacity utilisation", "working-capital days", "capital expenditure", "order book"),
            "Capital intensity and working capital are not captured by the available financial data."),

    CONSUMER(List.of(),
            List.of("volume growth", "gross margin", "same-store sales", "distribution reach"),
            "Volume and pricing split is not captured by the available financial data."),

    OTHER(List.of(), List.of(), "No sector-specific framework is applied for this industry."),

    UNKNOWN(List.of(), List.of(),
            "The company's sector could not be identified, so no sector-specific interpretation may be applied.");

    private final List<String> lessMeaningfulMetrics;
    private final List<String> sectorMetrics;
    private final String note;

    IndustryGroup(List<String> lessMeaningfulMetrics, List<String> sectorMetrics, String note) {
        this.lessMeaningfulMetrics = lessMeaningfulMetrics;
        this.sectorMetrics = sectorMetrics;
        this.note = note;
    }

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
