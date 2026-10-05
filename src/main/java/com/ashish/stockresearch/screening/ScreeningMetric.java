package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The metrics a screening rule may test - a fixed list, never a free string, so a rule can only name a
 * metric the snapshot actually records and the application calculates or checks.
 *
 * <p>Fiscal-year spans are exact: a 5-year metric is measured over exactly the last 5 fiscal years, never
 * over fewer. The company's filings supply six fiscal years, so a 5-year growth rate has both its ends.
 * Where only the market-data provider's four years are available, a 5-year metric is UNAVAILABLE and
 * says so. Each span-measured metric belongs to a {@link Series}; which span a request in words means
 * is decided from its words ({@code CriteriaValidator}), never left to the model.
 *
 * <p>The list is also the whitelist a request in words is checked against (roadmap Step 4): a metric
 * not named here cannot be screened on, and each metric states the range of thresholds that make sense
 * for it.
 *
 * @param sectorKey the application's metric name, used to check it against each industry group's
 *                  non-primary metrics ({@code IndustryGroup#isNonPrimary})
 */
public enum ScreeningMetric {

    ROE_3Y_AVG_PERCENT("ROE 3y avg", "return on equity, average of the last 3 fiscal years", Unit.PERCENT,
            "returnOnEquityPercent", "-100", "100", Series.ROE_AVERAGE, 3),
    ROE_5Y_AVG_PERCENT("ROE 5y avg", "return on equity, average of the last 5 fiscal years", Unit.PERCENT,
            "returnOnEquityPercent", "-100", "100", Series.ROE_AVERAGE, 5),
    ROE_PERCENT("ROE", "return on equity, latest fiscal year", Unit.PERCENT, "returnOnEquityPercent", "-100", "100"),
    ROA_PERCENT("ROA", "return on assets, latest fiscal year", Unit.PERCENT, "returnOnAssetsPercent", "-50", "50"),
    ROCE_PERCENT("ROCE", "return on capital employed, latest fiscal year", Unit.PERCENT,
            "returnOnCapitalEmployedPercent", "-100", "100"),
    OPERATING_MARGIN_PERCENT("Operating margin", "operating margin, latest fiscal year", Unit.PERCENT,
            "operatingMarginPercent", "-100", "100"),
    DEBT_TO_EQUITY_MULTIPLE("D/E", "debt-to-equity, latest fiscal year end", Unit.MULTIPLE, "debtToEquityMultiple",
            "0", "10"),
    REVENUE_CAGR_3Y_PERCENT("Revenue CAGR 3y", "revenue CAGR over the last 3 fiscal years", Unit.PERCENT,
            "revenueCagrPercent", "-50", "100", Series.REVENUE_CAGR, 3),
    REVENUE_CAGR_5Y_PERCENT("Revenue CAGR 5y", "revenue CAGR over the last 5 fiscal years", Unit.PERCENT,
            "revenueCagrPercent", "-50", "100", Series.REVENUE_CAGR, 5),
    NET_PROFIT_CAGR_3Y_PERCENT("Profit CAGR 3y", "net profit CAGR over the last 3 fiscal years", Unit.PERCENT,
            "netProfitCagrPercent", "-50", "100", Series.NET_PROFIT_CAGR, 3),
    NET_PROFIT_CAGR_5Y_PERCENT("Profit CAGR 5y", "net profit CAGR over the last 5 fiscal years", Unit.PERCENT,
            "netProfitCagrPercent", "-50", "100", Series.NET_PROFIT_CAGR, 5),
    OCF_TO_PAT_3Y_MULTIPLE("OCF/PAT 3y", "operating cash flow to net profit over the last 3 fiscal years",
            Unit.MULTIPLE, "ocfToPat3yMultiple", "-5", "5", Series.OCF_TO_PAT, 3),
    OCF_TO_PAT_5Y_MULTIPLE("OCF/PAT 5y", "operating cash flow to net profit over the last 5 fiscal years",
            Unit.MULTIPLE, "ocfToPat5yMultiple", "-5", "5", Series.OCF_TO_PAT, 5),
    DIVIDENDS_TO_OCF_PERCENT("Dividends/OCF", "dividends paid as a share of operating cash flow, latest fiscal year",
            Unit.PERCENT, "dividendsToOcfPercent", "0", "200"),
    TRAILING_PE_MULTIPLE("P/E", "trailing P/E at the snapshot's share price", Unit.MULTIPLE, "trailingPe",
            "0", "200"),
    PRICE_TO_BOOK_MULTIPLE("P/B", "price-to-book at the snapshot's share price", Unit.MULTIPLE, "priceToBook",
            "0", "50"),
    EARNINGS_YIELD_PERCENT("Earnings yield", "earnings yield (100 / trailing P/E) at the snapshot's share price",
            Unit.PERCENT, "earningsYieldPercent", "0", "50"),
    DIVIDEND_YIELD_PERCENT("Dividend yield", "dividend yield at the snapshot's share price", Unit.PERCENT,
            "dividendYieldPercent", "0", "20");

    private final String shortLabel;
    private final String description;
    private final Unit unit;
    private final String sectorKey;
    private final BigDecimal lowestThreshold;
    private final BigDecimal highestThreshold;
    private final Series series;
    private final int years;

    /**
     * A measure taken over a span of fiscal years, offered over more than one span. {@code defaultYears} is
     * the span a request means when its words name none - the span the presets use.
     */
    public enum Series {
        ROE_AVERAGE("average return on equity", 3),
        REVENUE_CAGR("revenue growth (CAGR)", 5),
        NET_PROFIT_CAGR("net profit growth (CAGR)", 5),
        OCF_TO_PAT("operating cash flow to net profit", 3);

        private final String description;
        private final int defaultYears;

        Series(String description, int defaultYears) {
            this.description = description;
            this.defaultYears = defaultYears;
        }

        public String description() {
            return description;
        }

        public int defaultYears() {
            return defaultYears;
        }
    }

    ScreeningMetric(String shortLabel, String description, Unit unit, String sectorKey, String lowestThreshold,
                    String highestThreshold) {
        this(shortLabel, description, unit, sectorKey, lowestThreshold, highestThreshold, null, 0);
    }

    ScreeningMetric(String shortLabel, String description, Unit unit, String sectorKey, String lowestThreshold,
                    String highestThreshold, Series series, int years) {
        this.shortLabel = shortLabel;
        this.description = description;
        this.unit = unit;
        this.sectorKey = sectorKey;
        this.lowestThreshold = new BigDecimal(lowestThreshold);
        this.highestThreshold = new BigDecimal(highestThreshold);
        this.series = series;
        this.years = years;
    }

    /** The series this metric measures over a span of fiscal years, or empty for a single-year or price metric. */
    public Optional<Series> series() {
        return Optional.ofNullable(series);
    }

    /** The number of fiscal years the metric spans; 0 for a metric that spans none. */
    public int years() {
        return years;
    }

    /**
     * The snapshot metric holding the same figure as a report metric ("returnOnEquityPercent" is ROE_PERCENT, the
     * latest fiscal year's): the one metric with that key, or the one of them that spans no fiscal years. Empty
     * when the report's figure has no single counterpart - a growth rate whose span may differ from the snapshot's.
     */
    public static Optional<ScreeningMetric> forReportMetric(String metricKey) {
        List<ScreeningMetric> same = Arrays.stream(values()).filter(metric -> metric.sectorKey.equals(metricKey)).toList();
        if (same.size() == 1) {
            return Optional.of(same.get(0));
        }
        return same.stream().filter(metric -> metric.series == null).findFirst();
    }

    /** The metric of {@code series} measured over {@code years} fiscal years, if the application offers that span. */
    public static Optional<ScreeningMetric> of(Series series, int years) {
        return Arrays.stream(values()).filter(metric -> metric.series == series && metric.years == years).findFirst();
    }

    /** The spans {@code series} is offered over, shortest first. */
    public static List<Integer> spans(Series series) {
        return Arrays.stream(values()).filter(metric -> metric.series == series).map(ScreeningMetric::years)
                .sorted().toList();
    }

    /**
     * The lowest threshold a rule on this metric may use, in the metric's unit. Outside these bounds a
     * threshold is almost certainly a misreading - 50 for a D/E multiple meant as 50%, 0.18 for an 18% ROE -
     * so a requested criterion outside them is rejected, never clamped or converted.
     */
    public BigDecimal lowestThreshold() {
        return lowestThreshold;
    }

    /** The highest threshold a rule on this metric may use; see {@link #lowestThreshold()}. */
    public BigDecimal highestThreshold() {
        return highestThreshold;
    }

    public boolean withinBounds(BigDecimal threshold) {
        return threshold.compareTo(lowestThreshold) >= 0 && threshold.compareTo(highestThreshold) <= 0;
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
