package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.FinancialDataProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.calc.FinancialUnits.ProviderScale;
import com.ashish.stockresearch.research.calc.FiscalCalendar;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ResearchStatus;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reported financials backed by Yahoo Finance: headline TTM figures from the
 * {@code financialData} / {@code defaultKeyStatistics} modules, dated
 * quarterly results from the {@code earnings} module, and fiscal-year
 * statement lines from the fundamentals time series.
 *
 * <p>Everything is normalised here, in Java, before it leaves the provider,
 * and only once {@link YahooFieldSemantics} has confirmed from Yahoo's own
 * formatted value what each raw number means:
 * <ul>
 *   <li>Absolute amounts confirmed as whole currency units become
 *       {@link Unit#INR_CRORE} (or {@link Unit#USD_MILLION} for USD-reported
 *       accounts).</li>
 *   <li>Ratios confirmed as fractions are multiplied by 100; ratios confirmed
 *       as percentage points are kept. A ratio whose form cannot be confirmed
 *       - including {@code debtToEquity}, whatever its name suggests - is
 *       UNAVAILABLE.</li>
 *   <li>Every period is built from a date Yahoo supplied and labelled by
 *       {@link FiscalCalendar}; periods ending after today, or published
 *       before they ended, are rejected by {@link FinancialDataValidator}.</li>
 * </ul>
 *
 * <p>Deliberate omissions, both driven by the "never invent a number" rule:
 * ROCE (Yahoo publishes no capital-employed figure) and Yahoo's quarterly
 * time series (it lags the {@code earnings} module by a year or more for
 * Indian listings, so using it would present a stale quarter as the latest).
 *
 * <p>The reporting currency is read from {@code financialCurrency}, never
 * assumed. Yahoo reports some Indian companies' accounts in USD.
 */
@Component
@Profile("!mock")
class YahooFinanceFinancialDataProvider implements FinancialDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceFinancialDataProvider.class);
    /**
     * Exactly the source the data came from - never upgraded to "company filings" or
     * "exchange data", which this application does not retrieve.
     */
    static final String SOURCE = "Yahoo Finance";
    private static final String MODULES = "financialData,defaultKeyStatistics,price,earnings";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int ANNUAL_HISTORY_YEARS = 6;
    private static final String REVENUE = "annualTotalRevenue";
    private static final String NET_INCOME = "annualNetIncome";
    private static final String OPERATING_INCOME = "annualOperatingIncome";
    private static final String EQUITY = "annualStockholdersEquity";
    private static final String ASSETS = "annualTotalAssets";
    private static final String DEBT = "annualTotalDebt";
    private static final List<String> ANNUAL_TYPES =
            List.of(REVENUE, NET_INCOME, OPERATING_INCOME, EQUITY, ASSETS, DEBT);

    private final YahooSymbolResolver symbolResolver;
    private final YahooQuoteSummaryClient quoteSummaryClient;
    private final FinancialDataValidator validator;

    YahooFinanceFinancialDataProvider(YahooSymbolResolver symbolResolver,
                                      YahooQuoteSummaryClient quoteSummaryClient,
                                      FinancialDataValidator validator) {
        this.symbolResolver = symbolResolver;
        this.quoteSummaryClient = quoteSummaryClient;
        this.validator = validator;
    }

    @Override
    public ReportedFinancials getReportedFinancials(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));

        YahooQuoteSummaryResponse.Result result = quoteSummaryClient.fetch(resolution.providerSymbol(), MODULES);
        YahooQuoteSummaryResponse.FinancialData financials = result.financialData();
        YahooQuoteSummaryResponse.DefaultKeyStatistics statistics = result.defaultKeyStatistics();
        if (financials == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance publishes no financial data for '%s'".formatted(resolution.providerSymbol()));
        }

        String currency = financials.financialCurrency();
        LocalDate fiscalYearEnd = statistics != null ? toUtcDate(statistics.lastFiscalYearEnd()) : null;
        LocalDate quarterEnd = statistics != null ? toUtcDate(statistics.mostRecentQuarter()) : null;
        FiscalCalendar calendar = FiscalCalendar.forFiscalYearEnd(fiscalYearEnd);
        List<DataGap> gaps = new ArrayList<>();

        List<QuarterlyResult> quarters = quarters(result.earnings(), calendar, currency, gaps);
        ReportingPeriod latestQuarter = latestQuarter(quarters, calendar.quarter(quarterEnd), gaps);
        ReportingPeriod ttm = validPeriod(calendar.trailingTwelveMonths(latestQuarter.end()), gaps, "TTM figures");
        ReportingPeriod balanceDate = latestQuarter.known()
                ? ReportingPeriod.pointInTime(latestQuarter.end()) : ReportingPeriod.unknown();
        ReportingPeriod latestFiscalYear = validPeriod(calendar.fiscalYear(fiscalYearEnd), gaps, "latest fiscal year");
        LocalDate latestPublishedAt = quarters.isEmpty() ? null
                : quarters.get(quarters.size() - 1).revenue().source() != null
                        ? quarters.get(quarters.size() - 1).revenue().source().publishedAt() : null;

        SourceInfo base = new SourceInfo(SOURCE, SourceType.MARKET_DATA_PROVIDER, null, null, latestQuarter.end());
        HeadlineFinancials headline = new HeadlineFinancials(
                amount("revenue", financials.totalRevenue(), currency, ttm, base, "financialData.totalRevenue"),
                amount("netProfit", statistics != null ? statistics.netIncomeToCommon() : null, currency, ttm, base,
                        "defaultKeyStatistics.netIncomeToCommon"),
                amount("ebitda", financials.ebitda(), currency, ttm, base, "financialData.ebitda"),
                amount("freeCashFlow", financials.freeCashflow(), currency, ttm, base, "financialData.freeCashflow"),
                amount("totalDebt", financials.totalDebt(), currency, balanceDate, base, "financialData.totalDebt"),
                amount("totalCash", financials.totalCash(), currency, balanceDate, base, "financialData.totalCash"),
                ratio("operatingMarginPercent", financials.operatingMargins(), ttm, base, "financialData.operatingMargins"),
                ratio("netProfitMarginPercent", financials.profitMargins(), ttm, base, "financialData.profitMargins"),
                ratio("returnOnEquityPercent", financials.returnOnEquity(), ttm, base, "financialData.returnOnEquity"),
                ratio("returnOnAssetsPercent", financials.returnOnAssets(), ttm, base, "financialData.returnOnAssets"),
                ratio("debtToEquityPercent", financials.debtToEquity(), balanceDate, base, "financialData.debtToEquity"),
                ratio("quarterlyRevenueGrowthYoyPercent", financials.revenueGrowth(), latestQuarter, base,
                        "financialData.revenueGrowth"),
                ratio("quarterlyEarningsGrowthYoyPercent", financials.earningsGrowth(), latestQuarter, base,
                        "financialData.earningsGrowth"),
                FinancialDataPoint.unavailable("returnOnCapitalEmployedPercent", Unit.PERCENT, ttm, base,
                        "Yahoo Finance publishes no capital-employed figure, so ROCE is not available from this source"));

        List<AnnualFinancials> annual = annualHistory(resolution.providerSymbol(), calendar, gaps);

        log.info("Retrieved Yahoo Finance financials for {} (currency={}, latest quarter={}, {} quarter(s), {} fiscal year(s))",
                resolution.providerSymbol(), currency, latestQuarter.label(), quarters.size(), annual.size());

        return new ReportedFinancials(
                resolution.symbol(),
                resolution.exchange(),
                companyName(resolution, result.price()),
                currency,
                latestQuarter,
                latestFiscalYear,
                headline,
                quarters,
                annual,
                List.copyOf(gaps),
                new DataProvenance(SOURCE, SourceType.MARKET_DATA_PROVIDER, DataFreshness.PERIODIC_FINANCIALS,
                        Instant.now(), latestPublishedAt, latestQuarter.end(), latestQuarter.end(),
                        provenanceNote(resolution, currency)));
    }

    /**
     * Joins the earnings module's dated quarters with its revenue/earnings
     * figures. A quarter with no period-end date cannot be labelled and is
     * skipped; one that fails period validation is excluded and named in the
     * gaps rather than presented as reported.
     */
    private List<QuarterlyResult> quarters(YahooQuoteSummaryResponse.Earnings earnings, FiscalCalendar calendar,
                                           String currency, List<DataGap> gaps) {
        if (earnings == null || earnings.earningsChart() == null || earnings.earningsChart().quarterly() == null) {
            gaps.add(new DataGap("Financials", "recentQuarters", "Yahoo Finance returned no dated quarterly results"));
            return List.of();
        }
        Map<String, YahooQuoteSummaryResponse.FinancialsQuarter> figures =
                earnings.financialsChart() == null || earnings.financialsChart().quarterly() == null
                        ? Map.of()
                        : earnings.financialsChart().quarterly().stream()
                                .filter(q -> q != null && q.date() != null)
                                .collect(Collectors.toMap(YahooQuoteSummaryResponse.FinancialsQuarter::date,
                                        Function.identity(), (a, b) -> b));

        List<QuarterlyResult> quarters = new ArrayList<>();
        for (YahooQuoteSummaryResponse.EarningsQuarter dated : earnings.earningsChart().quarterly()) {
            if (dated == null) {
                continue;
            }
            LocalDate end = toUtcDate(dated.periodEndDate());
            if (end == null) {
                gaps.add(new DataGap("Financials", "quarter " + dated.calendarQuarter(),
                        "Yahoo Finance gave no period-end date, so the quarter cannot be identified"));
                continue;
            }
            ReportingPeriod period = calendar.quarter(end);
            LocalDate published = toExchangeDate(dated.reportedDate());
            Optional<String> problem = validator.periodProblem(period, published);
            if (problem.isPresent()) {
                gaps.add(new DataGap("Financials", "quarter " + period.label(), problem.get()));
                continue;
            }
            YahooQuoteSummaryResponse.FinancialsQuarter figure = figures.get(dated.calendarQuarter());
            SourceInfo source = new SourceInfo(SOURCE, SourceType.MARKET_DATA_PROVIDER, null, published, end);
            quarters.add(new QuarterlyResult(period,
                    amount("quarterlyRevenue", figure != null ? figure.revenue() : null, currency, period, source,
                            "earnings.financialsChart.quarterly.revenue"),
                    amount("quarterlyNetProfit", figure != null ? figure.earnings() : null, currency, period, source,
                            "earnings.financialsChart.quarterly.earnings"),
                    earningsPerShare(dated.actual(), currency, period, source)));
        }
        quarters.sort(Comparator.comparing(q -> q.period().end()));
        return List.copyOf(quarters);
    }

    /**
     * The latest reported quarter: the newest validated quarter from the
     * earnings module, which carries a publication date. If that is absent,
     * {@code mostRecentQuarter} is used only if it passes validation;
     * otherwise the period is UNKNOWN. When the two disagree, that is
     * recorded as a gap rather than silently resolved.
     */
    private ReportingPeriod latestQuarter(List<QuarterlyResult> quarters, ReportingPeriod mostRecentQuarter,
                                          List<DataGap> gaps) {
        if (!quarters.isEmpty()) {
            ReportingPeriod latest = quarters.get(quarters.size() - 1).period();
            if (mostRecentQuarter.known() && !mostRecentQuarter.end().equals(latest.end())) {
                gaps.add(new DataGap("Financials", "latestReportedQuarter",
                        ("Yahoo's mostRecentQuarter (%s) disagrees with its latest dated quarterly result (%s); "
                                + "the dated result is used").formatted(mostRecentQuarter.end(), latest.end())));
            }
            return latest;
        }
        return validPeriod(mostRecentQuarter, gaps, "latest reported quarter");
    }

    private ReportingPeriod validPeriod(ReportingPeriod period, List<DataGap> gaps, String what) {
        Optional<String> problem = validator.periodProblem(period, null);
        if (problem.isPresent()) {
            gaps.add(new DataGap("Financials", what, problem.get() + "; treated as UNKNOWN"));
            return ReportingPeriod.unknown();
        }
        return period;
    }

    /**
     * Fiscal-year statement lines. A failure here costs only the history -
     * the headline figures still stand - so it becomes a gap, not an error.
     */
    private List<AnnualFinancials> annualHistory(String providerSymbol, FiscalCalendar calendar, List<DataGap> gaps) {
        Map<String, List<YahooQuoteSummaryClient.TimeseriesValue>> series;
        try {
            LocalDate today = validator.today();
            series = quoteSummaryClient.fetchTimeseries(providerSymbol, ANNUAL_TYPES,
                    today.minusYears(ANNUAL_HISTORY_YEARS), today.plusDays(1));
        } catch (ResearchDataUnavailableException ex) {
            gaps.add(new DataGap("Financials", "annualHistory",
                    "Fiscal-year statement data could not be retrieved: " + ex.getMessage()));
            return List.of();
        }

        Map<LocalDate, Map<String, YahooQuoteSummaryClient.TimeseriesValue>> byYearEnd = new TreeMap<>();
        series.forEach((type, values) -> values.stream()
                .filter(value -> value.periodType() == null || "12M".equals(value.periodType()))
                .forEach(value -> byYearEnd.computeIfAbsent(value.asOfDate(), date -> new java.util.HashMap<>())
                        .put(type, value)));

        List<AnnualFinancials> years = new ArrayList<>();
        byYearEnd.forEach((end, values) -> {
            ReportingPeriod period = calendar.fiscalYear(end);
            Optional<String> problem = validator.periodProblem(period, null);
            if (problem.isPresent()) {
                gaps.add(new DataGap("Financials", "fiscal year " + period.label(), problem.get()));
                return;
            }
            SourceInfo source = new SourceInfo(SOURCE, SourceType.MARKET_DATA_PROVIDER, null, null, end);
            ReportingPeriod closing = ReportingPeriod.pointInTime(end);
            years.add(new AnnualFinancials(period,
                    annualAmount("annualRevenue", values.get(REVENUE), period, source, REVENUE),
                    annualAmount("annualNetProfit", values.get(NET_INCOME), period, source, NET_INCOME),
                    annualAmount("annualOperatingIncome", values.get(OPERATING_INCOME), period, source, OPERATING_INCOME),
                    annualAmount("shareholdersEquity", values.get(EQUITY), closing, source, EQUITY),
                    annualAmount("totalAssets", values.get(ASSETS), closing, source, ASSETS),
                    annualAmount("totalDebt", values.get(DEBT), closing, source, DEBT)));
        });
        if (years.isEmpty()) {
            gaps.add(new DataGap("Financials", "annualHistory",
                    "Yahoo Finance returned no fiscal-year statement data"));
        }
        return List.copyOf(years);
    }

    private FinancialDataPoint annualAmount(String metric, YahooQuoteSummaryClient.TimeseriesValue value,
                                            ReportingPeriod period, SourceInfo source, String type) {
        return amount(metric, value != null ? value.value() : null, value != null ? value.currencyCode() : null,
                period, source, "fundamentals-timeseries." + type);
    }

    /**
     * An absolute amount. Its meaning (whole currency units) must be confirmed
     * by Yahoo's formatted string before it is normalised; an unknown or
     * unsupported currency is never labelled as rupees.
     */
    private FinancialDataPoint amount(String metric, YahooValue raw, String currency, ReportingPeriod period,
                                      SourceInfo base, String field) {
        SourceInfo source = base.withField(field);
        if (raw == null) {
            return FinancialDataPoint.unavailable(metric, null, period, source, "Yahoo Finance did not publish " + field);
        }
        if (YahooFieldSemantics.verify(raw, YahooFieldSemantics.AMOUNT).isEmpty()) {
            return FinancialDataPoint.semanticsUnverified(metric, raw.raw(), period, source,
                    "Yahoo's formatted value %s does not confirm an amount in whole currency units".formatted(quoted(raw)));
        }
        return FinancialUnits.normalizeAmount(raw.raw(), ProviderScale.ONES, currency)
                .map(normalized -> FinancialDataPoint.reported(metric, raw.raw(), ProviderUnit.WHOLE_CURRENCY_UNITS,
                        normalized.value(), normalized.unit(), period, source))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, null, period, source, currency == null
                        ? "The reporting currency is unknown, so this amount cannot be labelled"
                        : "Amount is reported in %s, which this application does not normalise".formatted(currency)));
    }

    /**
     * A ratio. Whether Yahoo sent a fraction (0.2679) or percentage points
     * (7.27) is established from its formatted string, per value - not from
     * the field name and not from the number's size. If that cannot be
     * established, the metric is UNAVAILABLE rather than guessed.
     */
    private FinancialDataPoint ratio(String metric, YahooValue raw, ReportingPeriod period, SourceInfo base, String field) {
        SourceInfo source = base.withField(field);
        if (raw == null) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, period, source,
                    "Yahoo Finance did not publish " + field);
        }
        Optional<ProviderUnit> meaning = YahooFieldSemantics.verify(raw, YahooFieldSemantics.RATIO);
        if (meaning.isEmpty()) {
            return FinancialDataPoint.semanticsUnverified(metric, raw.raw(), period, source,
                    "Yahoo's formatted value %s does not establish whether the raw value is a fraction or a percentage"
                            .formatted(quoted(raw)));
        }
        BigDecimal percent = meaning.get() == ProviderUnit.FRACTION
                ? FinancialUnits.fractionToPercent(raw.raw())
                : raw.raw().setScale(2, RoundingMode.HALF_UP);
        return FinancialDataPoint.reported(metric, raw.raw(), meaning.get(), percent, Unit.PERCENT, period, source);
    }

    private FinancialDataPoint earningsPerShare(YahooValue raw, String currency, ReportingPeriod period, SourceInfo base) {
        SourceInfo source = base.withField("earnings.earningsChart.quarterly.actual");
        if (raw == null) {
            return FinancialDataPoint.unavailable("earningsPerShare", Unit.INR_PER_SHARE, period, source,
                    "Yahoo Finance published no EPS for this quarter");
        }
        if (!"INR".equalsIgnoreCase(currency)) {
            return FinancialDataPoint.unavailable("earningsPerShare", null, period, source,
                    "EPS currency is ambiguous for accounts not reported in INR");
        }
        if (YahooFieldSemantics.verify(raw, YahooFieldSemantics.PER_SHARE).isEmpty()) {
            return FinancialDataPoint.semanticsUnverified("earningsPerShare", raw.raw(), period, source,
                    "Yahoo's formatted value %s does not confirm a per-share amount".formatted(quoted(raw)));
        }
        return FinancialDataPoint.reported("earningsPerShare", raw.raw(), ProviderUnit.PER_SHARE,
                raw.raw().setScale(2, RoundingMode.HALF_UP), Unit.INR_PER_SHARE, period, source);
    }

    private static String quoted(YahooValue value) {
        return value.fmt() == null ? "(none sent)" : "'" + value.fmt() + "'";
    }

    private String provenanceNote(YahooSymbolResolver.Resolution resolution, String currency) {
        StringBuilder note = new StringBuilder(
                "Source: Yahoo Finance (unofficial, undocumented endpoints). Figures describe past reporting "
                        + "periods; they are NOT live. Each amount is normalised to crore (or US$ million) and each "
                        + "ratio to a percentage only after Yahoo's own formatted value confirmed what the raw number "
                        + "means; rawValue and providerUnit show exactly what Yahoo sent. REPORTED values are "
                        + "Yahoo's; CALCULATED values were computed by this application. Anything UNAVAILABLE is "
                        + "unknown, not zero.");
        if (currency != null && !"INR".equalsIgnoreCase(currency)) {
            note.append(" IMPORTANT: the accounts are reported in ").append(currency)
                    .append(", not INR, even though ").append(resolution.symbol())
                    .append(" trades in rupees on ").append(resolution.exchange())
                    .append(". Do not present these as rupee figures or convert them.");
        }
        return note.toString();
    }

    /** Yahoo's fiscal dates (period ends) are midnight UTC on the date itself. */
    private LocalDate toUtcDate(BigDecimal epochSeconds) {
        return epochSeconds == null ? null
                : Instant.ofEpochSecond(epochSeconds.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Announcement timestamps are real instants; read them on the exchange's calendar. */
    private LocalDate toExchangeDate(BigDecimal epochSeconds) {
        return epochSeconds == null ? null
                : Instant.ofEpochSecond(epochSeconds.longValue()).atZone(EXCHANGE_ZONE).toLocalDate();
    }

    private String companyName(YahooSymbolResolver.Resolution resolution, YahooQuoteSummaryResponse.Price price) {
        if (price != null && price.longName() != null) {
            return price.longName();
        }
        return resolution.meta() != null && resolution.meta().longName() != null
                ? resolution.meta().longName() : resolution.symbol();
    }
}
