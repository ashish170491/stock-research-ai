package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.calc.FiscalCalendar;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Normalised HDFC Bank figures (crore) as the Yahoo provider produces them
 * from live data, with the same metric names and source fields, for
 * deterministic service-level tests.
 */
public final class ResearchFixtures {

    public static final FiscalCalendar CALENDAR = FiscalCalendar.indian();
    public static final SourceInfo SOURCE = new SourceInfo("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER, null, null, null);
    public static final LocalDate LATEST_QUARTER_END = LocalDate.of(2026, 6, 30);

    private ResearchFixtures() {
    }

    public static FinancialDataPoint crore(String metric, String field, String value, ReportingPeriod period) {
        BigDecimal crore = new BigDecimal(value);
        return FinancialDataPoint.reported(metric, crore.multiply(FinancialUnits.RUPEES_PER_CRORE),
                ProviderUnit.WHOLE_CURRENCY_UNITS, crore, Unit.INR_CRORE, period, SOURCE.withField(field));
    }

    public static FinancialDataPoint percent(String metric, String field, String value, ReportingPeriod period) {
        BigDecimal percent = new BigDecimal(value);
        return FinancialDataPoint.reported(metric, percent.movePointLeft(2), ProviderUnit.FRACTION, percent,
                Unit.PERCENT, period, SOURCE.withField(field));
    }

    public static FinancialDataPoint missing(String metric, String field, ReportingPeriod period) {
        return FinancialDataPoint.unavailable(metric, Unit.INR_CRORE, period, SOURCE.withField(field),
                "Yahoo Finance did not publish " + field);
    }

    /** The year with EBIT, current liabilities and operating cash flow; a null one is not published. */
    public static AnnualFinancials withCapitalAndCash(AnnualFinancials year, String ebit, String currentLiabilities,
                                                      String operatingCashFlow) {
        String ts = "fundamentals-timeseries.";
        ReportingPeriod period = year.period();
        ReportingPeriod closing = ReportingPeriod.pointInTime(period.end());
        return new AnnualFinancials(period, year.revenue(), year.netProfit(), year.operatingIncome(),
                year.shareholdersEquity(), year.totalAssets(), year.totalDebt(),
                ebit == null ? missing("ebit", ts + "annualEBIT", period) : crore("ebit", ts + "annualEBIT", ebit, period),
                currentLiabilities == null ? missing("currentLiabilities", ts + "annualCurrentLiabilities", closing)
                        : crore("currentLiabilities", ts + "annualCurrentLiabilities", currentLiabilities, closing),
                operatingCashFlow == null ? missing("operatingCashFlow", ts + "annualOperatingCashFlow", period)
                        : crore("operatingCashFlow", ts + "annualOperatingCashFlow", operatingCashFlow, period));
    }

    public static AnnualFinancials year(int endYear, String revenue, String netProfit, String equity, String assets) {
        return year(endYear, revenue, netProfit, equity, assets, null);
    }

    public static AnnualFinancials year(int endYear, String revenue, String netProfit, String equity, String assets,
                                        String debt) {
        LocalDate end = LocalDate.of(endYear, 3, 31);
        ReportingPeriod period = CALENDAR.fiscalYear(end);
        ReportingPeriod closing = ReportingPeriod.pointInTime(end);
        String ts = "fundamentals-timeseries.";
        return new AnnualFinancials(period,
                crore("annualRevenue", ts + "annualTotalRevenue", revenue, period),
                crore("annualNetProfit", ts + "annualNetIncome", netProfit, period),
                missing("annualOperatingIncome", ts + "annualOperatingIncome", period),
                crore("shareholdersEquity", ts + "annualStockholdersEquity", equity, closing),
                crore("totalAssets", ts + "annualTotalAssets", assets, closing),
                debt == null ? missing("totalDebt", ts + "annualTotalDebt", closing)
                        : crore("totalDebt", ts + "annualTotalDebt", debt, closing),
                missing("ebit", ts + "annualEBIT", period),
                missing("currentLiabilities", ts + "annualCurrentLiabilities", closing),
                missing("operatingCashFlow", ts + "annualOperatingCashFlow", period));
    }

    public static List<AnnualFinancials> hdfcAnnual() {
        return List.of(
                year(2023, "120537.50", "49544.69", "291220.07", "2575562.40", "315179.28"),
                year(2024, "160496.85", "62265.66", "694732.74", "4411857.30", "809355.21"),
                year(2025, "183864.11", "67350.83", "767689.14", "4818767.11", "732610.61"),
                year(2026, "192566.78", "70479.34", "816739.56", "5262119.32", "664364.04"));
    }

    public static QuarterlyResult quarter(LocalDate end, String revenue, String netProfit) {
        ReportingPeriod period = CALENDAR.quarter(end);
        return new QuarterlyResult(period,
                crore("quarterlyRevenue", "earnings.financialsChart.quarterly.revenue", revenue, period),
                crore("quarterlyNetProfit", "earnings.financialsChart.quarterly.earnings", netProfit, period),
                FinancialDataPoint.reported("earningsPerShare", new BigDecimal("12.35"), ProviderUnit.PER_SHARE,
                        new BigDecimal("12.35"), Unit.INR_PER_SHARE, period,
                        SOURCE.withField("earnings.earningsChart.quarterly.actual")));
    }

    public static HeadlineFinancials hdfcHeadline() {
        ReportingPeriod ttm = CALENDAR.trailingTwelveMonths(LATEST_QUARTER_END);
        ReportingPeriod balance = ReportingPeriod.pointInTime(LATEST_QUARTER_END);
        ReportingPeriod quarter = CALENDAR.quarter(LATEST_QUARTER_END);
        return new HeadlineFinancials(
                crore("revenue", "financialData.totalRevenue", "294964.43", ttm),
                crore("netProfit", "defaultKeyStatistics.netIncomeToCommon", "79012.77", ttm),
                missing("ebitda", "financialData.ebitda", ttm),
                missing("freeCashFlow", "financialData.freeCashflow", ttm),
                crore("totalDebt", "financialData.totalDebt", "565871.38", balance),
                crore("totalCash", "financialData.totalCash", "237766.63", balance),
                percent("operatingMarginPercent", "financialData.operatingMargins", "33.29", ttm)
                        .derivedFrom(List.of("financialData.totalRevenue")),
                percent("netProfitMarginPercent", "financialData.profitMargins", "26.79", ttm)
                        .derivedFrom(List.of("financialData.totalRevenue", "defaultKeyStatistics.netIncomeToCommon")),
                percent("returnOnEquityPercent", "financialData.returnOnEquity", "13.84", ttm),
                percent("returnOnAssetsPercent", "financialData.returnOnAssets", "1.75", ttm),
                missing("debtToEquityPercent", "financialData.debtToEquity", balance),
                percent("quarterlyRevenueGrowthYoyPercent", "financialData.revenueGrowth", "16.60", quarter)
                        .derivedFrom(List.of("financialData.totalRevenue")),
                percent("quarterlyEarningsGrowthYoyPercent", "financialData.earningsGrowth", "18.10", quarter),
                FinancialDataPoint.unavailable("returnOnCapitalEmployedPercent", Unit.PERCENT, ttm, SOURCE,
                        "No capital-employed figure"));
    }

    /** HDFC Bank's four real reported quarters, Q2 FY26 to Q1 FY27 - exactly the TTM period. */
    public static List<QuarterlyResult> hdfcQuarters() {
        return List.of(
                quarter(LocalDate.of(2025, 9, 30), "45901.52", "18641.28"),
                quarter(LocalDate.of(2025, 12, 31), "45868.84", "18653.75"),
                quarter(LocalDate.of(2026, 3, 31), "46280.45", "19221.05"),
                quarter(LATEST_QUARTER_END, "46355.55", "19059.72"));
    }

    /**
     * HDFC Bank's figures with the revenue conflict removed: TTM revenue equal to the sum of its four
     * quarters, and no provider margins derived from the other revenue definition. For testing the
     * calculations themselves on data that passes every source check.
     */
    public static ReportedFinancials consistentReported() {
        ReportingPeriod ttm = CALENDAR.trailingTwelveMonths(LATEST_QUARTER_END);
        HeadlineFinancials h = hdfcHeadline();
        HeadlineFinancials consistent = new HeadlineFinancials(
                crore("revenue", "financialData.totalRevenue", "184406.36", ttm), h.netProfit(), h.ebitda(),
                h.freeCashFlow(), h.totalDebt(), h.totalCash(),
                missing("operatingMarginPercent", "financialData.operatingMargins", ttm),
                missing("netProfitMarginPercent", "financialData.profitMargins", ttm),
                h.returnOnEquityPercent(), h.returnOnAssetsPercent(), h.debtToEquityPercent(),
                h.quarterlyRevenueGrowthYoyPercent(), h.quarterlyEarningsGrowthYoyPercent(),
                h.returnOnCapitalEmployedPercent());
        return reported(consistent, hdfcQuarters(), hdfcAnnual(), CALENDAR.quarter(LATEST_QUARTER_END));
    }

    public static ReportedFinancials hdfcReported() {
        return reported(hdfcHeadline(), hdfcQuarters(), hdfcAnnual(), CALENDAR.quarter(LATEST_QUARTER_END));
    }

    public static ReportedFinancials reported(HeadlineFinancials headline, List<QuarterlyResult> quarters,
                                              List<AnnualFinancials> annual, ReportingPeriod latestQuarter) {
        return new ReportedFinancials("HDFCBANK", "NSE", "HDFC Bank Limited", "INR", latestQuarter,
                CALENDAR.fiscalYear(LocalDate.of(2026, 3, 31)), headline, quarters, annual, List.of(), List.of(),
                new DataProvenance("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER, DataFreshness.PERIODIC_FINANCIALS,
                        Instant.now(), LocalDate.of(2026, 7, 18), LATEST_QUARTER_END, LATEST_QUARTER_END, "note"));
    }
}
