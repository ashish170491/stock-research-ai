package com.ashish.stockresearch.research.provider.mock;

import com.ashish.stockresearch.research.CompanyProfileProvider;
import com.ashish.stockresearch.research.FinancialDataProvider;
import com.ashish.stockresearch.research.HistoricalMarketDataProvider;
import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.calc.FiscalCalendar;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Development-only research provider, mirroring
 * {@code MockMarketDataProvider}. Returns clearly labelled sample data so
 * the multi-tool calling flow can be exercised end-to-end with no network
 * access, and only when the "mock" profile is explicitly enabled.
 *
 * Every figure here is MADE UP. The source string and provenance note say
 * so in every object, so a model reading this data cannot mistake it for
 * research.
 */
@Component
@Profile("mock")
public class MockStockResearchProvider
        implements CompanyProfileProvider, FinancialDataProvider, HistoricalMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(MockStockResearchProvider.class);
    private static final String SOURCE =
            "MOCK-SAMPLE-DATA (not real research data; run without the 'mock' profile for real data)";
    private static final String NOTE =
            "THIS IS FABRICATED SAMPLE DATA FOR DEVELOPMENT. Do not present any of these figures to a user "
                    + "as factual information about this company.";
    private static final FiscalCalendar CALENDAR = FiscalCalendar.indian();
    private static final LocalDate QUARTER_END = LocalDate.of(2026, 6, 30);
    private static final LocalDate FISCAL_YEAR_END = LocalDate.of(2026, 3, 31);

    @Override
    public CompanyProfile getCompanyProfile(String symbolOrName) {
        String symbol = normalize(symbolOrName);
        warn("company profile", symbol);
        LocalDate asOf = LocalDate.of(2026, 9, 1);
        return new CompanyProfile(symbol, "NSE", symbol + " Limited (sample)", "Sample Sector",
                "Sample Industry", "India", "https://example.invalid", 10_000,
                FinancialDataPoint.reported("marketCap", new BigDecimal("1000000000000"),
                        ProviderUnit.WHOLE_CURRENCY_UNITS, new BigDecimal("100000.00"), Unit.INR_CRORE,
                        ReportingPeriod.pointInTime(asOf), source(asOf)),
                FinancialDataPoint.reported("sharesOutstanding", new BigDecimal("1000000000"), ProviderUnit.SHARE_COUNT,
                        new BigDecimal("1000000000"), Unit.SHARES, ReportingPeriod.unknown(), source(null)),
                "Sample business summary for development purposes only.",
                List.of(),
                provenance(DataFreshness.REFERENCE, asOf, null));
    }

    @Override
    public ReportedFinancials getReportedFinancials(String symbolOrName) {
        String symbol = normalize(symbolOrName);
        warn("financial summary", symbol);
        ReportingPeriod quarter = CALENDAR.quarter(QUARTER_END);
        ReportingPeriod ttm = CALENDAR.trailingTwelveMonths(QUARTER_END);
        ReportingPeriod balance = ReportingPeriod.pointInTime(QUARTER_END);
        SourceInfo source = source(QUARTER_END);
        HeadlineFinancials headline = new HeadlineFinancials(
                crore("revenue", "10000", ttm), crore("netProfit", "1500", ttm), crore("ebitda", "2200", ttm),
                crore("freeCashFlow", "900", ttm), crore("totalDebt", "500", balance), crore("totalCash", "800", balance),
                percent("operatingMarginPercent", "20.00", ttm), percent("netProfitMarginPercent", "15.00", ttm),
                percent("returnOnEquityPercent", "18.00", ttm), percent("returnOnAssetsPercent", "12.00", ttm),
                percent("debtToEquityPercent", "25.00", balance),
                percent("quarterlyRevenueGrowthYoyPercent", "10.00", quarter),
                percent("quarterlyEarningsGrowthYoyPercent", "8.00", quarter),
                FinancialDataPoint.unavailable("returnOnCapitalEmployedPercent", Unit.PERCENT, ttm, source,
                        "Not available in sample data"));

        List<QuarterlyResult> quarters = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            LocalDate end = QUARTER_END.minusMonths(3L * i).withDayOfMonth(1).plusMonths(1).minusDays(1);
            ReportingPeriod period = CALENDAR.quarter(end);
            quarters.add(new QuarterlyResult(period, crore("quarterlyRevenue", String.valueOf(2400 + 50 * (3 - i)), period),
                    crore("quarterlyNetProfit", String.valueOf(360 + 10 * (3 - i)), period),
                    FinancialDataPoint.reported("earningsPerShare", new BigDecimal("3.60"), ProviderUnit.PER_SHARE,
                            new BigDecimal("3.60"), Unit.INR_PER_SHARE, period, source(end))));
        }
        List<AnnualFinancials> annual = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            LocalDate end = FISCAL_YEAR_END.minusYears(i);
            ReportingPeriod period = CALENDAR.fiscalYear(end);
            ReportingPeriod closing = ReportingPeriod.pointInTime(end);
            annual.add(new AnnualFinancials(period,
                    crore("annualRevenue", String.valueOf(8000 + 600 * (3 - i)), period),
                    crore("annualNetProfit", String.valueOf(1100 + 120 * (3 - i)), period),
                    crore("annualOperatingIncome", String.valueOf(1600 + 150 * (3 - i)), period),
                    crore("shareholdersEquity", String.valueOf(7000 + 700 * (3 - i)), closing),
                    crore("totalAssets", String.valueOf(15000 + 1000 * (3 - i)), closing),
                    crore("totalDebt", String.valueOf(500 + 20 * (3 - i)), closing)));
        }
        return new ReportedFinancials(symbol, "NSE", symbol + " Limited (sample)", "INR", quarter,
                CALENDAR.fiscalYear(FISCAL_YEAR_END), headline, quarters, annual, List.of(), List.of(),
                provenance(DataFreshness.PERIODIC_FINANCIALS, QUARTER_END, QUARTER_END));
    }

    @Override
    public PriceHistory getPriceHistory(String symbolOrName, int years) {
        String symbol = normalize(symbolOrName);
        warn("historical performance", symbol);
        LocalDate end = LocalDate.of(2026, 9, 1);
        List<PricePoint> closes = new ArrayList<>();
        for (int month = years * 12; month >= 0; month--) {
            double drift = 100 + (years * 12 - month) * 0.8 + (month % 7 == 0 ? -6 : 0);
            closes.add(new PricePoint(end.minusMonths(month), BigDecimal.valueOf(drift).setScale(2, RoundingMode.HALF_UP)));
        }
        return new PriceHistory(symbol, "NSE", "INR", closes, "Fabricated sample series (NOT REAL PRICES)",
                provenance(DataFreshness.HISTORICAL, end, null));
    }

    private FinancialDataPoint crore(String metric, String value, ReportingPeriod period) {
        BigDecimal crore = new BigDecimal(value);
        return FinancialDataPoint.reported(metric, crore.multiply(FinancialUnits.RUPEES_PER_CRORE),
                ProviderUnit.WHOLE_CURRENCY_UNITS, crore, Unit.INR_CRORE, period, source(period.end()));
    }

    private FinancialDataPoint percent(String metric, String value, ReportingPeriod period) {
        return FinancialDataPoint.reported(metric, new BigDecimal(value), ProviderUnit.PERCENTAGE,
                new BigDecimal(value), Unit.PERCENT, period, source(period.end()));
    }

    private SourceInfo source(LocalDate asOf) {
        return new SourceInfo(SOURCE, SourceType.OTHER, null, null, asOf);
    }

    private DataProvenance provenance(DataFreshness freshness, LocalDate asOf, LocalDate periodEnd) {
        return new DataProvenance(SOURCE, SourceType.OTHER, freshness, Instant.now(), null, asOf, periodEnd, NOTE);
    }

    private String normalize(String symbolOrName) {
        return symbolOrName.strip().toUpperCase();
    }

    private void warn(String capability, String symbol) {
        log.warn("Using MOCK research provider - {} for '{}' is SAMPLE DATA, not real information",
                capability, symbol);
    }
}
