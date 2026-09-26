package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.FinancialDataProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Financial summary backed by Yahoo Finance's {@code financialData} and
 * {@code defaultKeyStatistics} modules.
 *
 * Two deliberate omissions, both driven by the "never invent a number" rule:
 * <ul>
 *   <li><b>ROCE</b> - Yahoo publishes no capital-employed figure, and its
 *       {@code incomeStatementHistory} EBIT comes back as 0 for Indian
 *       listings, so any ROCE computed here would be a fabrication dressed
 *       as a ratio. It is left null and named in
 *       {@code unavailableMetrics}.</li>
 *   <li><b>Segment / quarterly detail</b> - out of scope for a headline summary.</li>
 * </ul>
 *
 * The reporting currency is read from {@code financialCurrency}, never
 * assumed. Yahoo reports some Indian companies' accounts in USD (sourced
 * from their US listing) even though the shares trade in INR, and silently
 * labelling those figures as rupees would overstate revenue by roughly 80x.
 */
@Component
@Profile("!mock")
class YahooFinanceFinancialDataProvider implements FinancialDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceFinancialDataProvider.class);
    private static final String MODULES = "financialData,defaultKeyStatistics,price";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final YahooSymbolResolver symbolResolver;
    private final YahooQuoteSummaryClient quoteSummaryClient;

    YahooFinanceFinancialDataProvider(YahooSymbolResolver symbolResolver,
                                      YahooQuoteSummaryClient quoteSummaryClient) {
        this.symbolResolver = symbolResolver;
        this.quoteSummaryClient = quoteSummaryClient;
    }

    @Override
    public FinancialSummary getFinancialSummary(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));

        YahooQuoteSummaryResponse.Result result =
                quoteSummaryClient.fetch(resolution.providerSymbol(), MODULES);
        YahooQuoteSummaryResponse.FinancialData financials = result.financialData();
        YahooQuoteSummaryResponse.DefaultKeyStatistics statistics = result.defaultKeyStatistics();

        if (financials == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance publishes no financial data for '%s'".formatted(resolution.providerSymbol()));
        }

        LocalDate quarterEnd = statistics != null ? toLocalDate(statistics.mostRecentQuarter()) : null;
        LocalDate fiscalYearEnd = statistics != null ? toLocalDate(statistics.lastFiscalYearEnd()) : null;
        String currency = financials.financialCurrency();

        Metrics metrics = new Metrics(
                financials.totalRevenue(),
                statistics != null ? statistics.netIncomeToCommon() : null,
                financials.ebitda(),
                toPercent(financials.operatingMargins()),
                toPercent(financials.profitMargins()),
                toPercent(financials.returnOnEquity()),
                toPercent(financials.returnOnAssets()),
                financials.totalDebt(),
                scale(financials.debtToEquity()), // Yahoo already reports this one as a percentage
                financials.totalCash(),
                financials.freeCashflow(),
                toPercent(financials.revenueGrowth()),
                toPercent(financials.earningsGrowth()));

        List<String> unavailable = describeUnavailableMetrics(metrics);
        log.info("Retrieved Yahoo Finance financial summary for {} (currency={}, {} metric(s) unavailable)",
                resolution.providerSymbol(), currency, unavailable.size());

        return new FinancialSummary(
                resolution.symbol(),
                resolution.exchange(),
                companyName(resolution, result.price()),
                currency,
                describeReportingPeriod(quarterEnd),
                quarterEnd,
                fiscalYearEnd,
                metrics.revenue(),
                metrics.netProfit(),
                metrics.ebitda(),
                metrics.operatingMarginPercent(),
                metrics.netProfitMarginPercent(),
                metrics.returnOnEquityPercent(),
                null, // ROCE - see class javadoc
                metrics.returnOnAssetsPercent(),
                metrics.totalDebt(),
                metrics.debtToEquityPercent(),
                metrics.totalCash(),
                metrics.freeCashFlow(),
                metrics.revenueGrowthPercent(),
                metrics.earningsGrowthPercent(),
                unavailable,
                provenance(resolution, currency, quarterEnd));
    }

    /** The numeric fields, already converted to the units the model reads, so the
     *  "what is missing" pass runs over the final values rather than the raw payload. */
    private record Metrics(
            BigDecimal revenue,
            BigDecimal netProfit,
            BigDecimal ebitda,
            BigDecimal operatingMarginPercent,
            BigDecimal netProfitMarginPercent,
            BigDecimal returnOnEquityPercent,
            BigDecimal returnOnAssetsPercent,
            BigDecimal totalDebt,
            BigDecimal debtToEquityPercent,
            BigDecimal totalCash,
            BigDecimal freeCashFlow,
            BigDecimal revenueGrowthPercent,
            BigDecimal earningsGrowthPercent
    ) {
    }

    private DataProvenance provenance(YahooSymbolResolver.Resolution resolution, String currency,
                                      LocalDate quarterEnd) {
        StringBuilder note = new StringBuilder(
                "Trailing-twelve-month and most-recent-quarter figures from company filings. NOT live figures. "
                        + "Absolute amounts are in whole units of the stated currency - NOT crore, lakh or "
                        + "millions - so divide by 10,000,000 before quoting a figure in crore. "
                        + "Every *Percent field is a percentage, not a multiple: debtToEquityPercent of 10.21 "
                        + "means total debt equals 10.21% of equity, i.e. a debt-to-equity ratio of 0.10x - "
                        + "it does NOT mean 10.21 times equity. "
                        + "Anything named in unavailableMetrics is unknown, not zero; ROCE is never available "
                        + "from this source and must not be estimated.");
        if (currency == null) {
            note.append(" The reporting currency is unknown, so absolute amounts cannot be safely "
                    + "interpreted as rupees.");
        } else if (!"INR".equalsIgnoreCase(currency)) {
            note.append(" IMPORTANT: amounts are reported in ").append(currency)
                    .append(", not INR, even though ").append(resolution.symbol())
                    .append(" trades in rupees on ").append(resolution.exchange())
                    .append(". Do not present these as rupee figures or convert them without an explicit rate.");
        }
        return new DataProvenance(
                "Yahoo Finance (unofficial/undocumented endpoint)",
                DataFreshness.PERIODIC_FILING,
                Instant.now(),
                quarterEnd,
                note.toString());
    }

    /**
     * Names every metric that came back null, so the model can report "not
     * available" explicitly instead of reading a missing field as an absent -
     * and therefore negligible - value. Just the names: the reason is stated
     * once in the provenance note rather than repeated on every entry, which
     * keeps a summary with many gaps from crowding out the actual figures in
     * the model's context.
     */
    private List<String> describeUnavailableMetrics(Metrics metrics) {
        List<String> unavailable = new ArrayList<>();
        // ROCE is never available from this source; the rest depend on the company.
        unavailable.add("returnOnCapitalEmployedPercent");
        addIfNull(unavailable, metrics.revenue(), "revenue");
        addIfNull(unavailable, metrics.netProfit(), "netProfit");
        addIfNull(unavailable, metrics.ebitda(), "ebitda");
        addIfNull(unavailable, metrics.operatingMarginPercent(), "operatingMarginPercent");
        addIfNull(unavailable, metrics.netProfitMarginPercent(), "netProfitMarginPercent");
        addIfNull(unavailable, metrics.returnOnEquityPercent(), "returnOnEquityPercent");
        addIfNull(unavailable, metrics.returnOnAssetsPercent(), "returnOnAssetsPercent");
        addIfNull(unavailable, metrics.totalDebt(), "totalDebt");
        addIfNull(unavailable, metrics.debtToEquityPercent(), "debtToEquityPercent");
        addIfNull(unavailable, metrics.totalCash(), "totalCash");
        addIfNull(unavailable, metrics.freeCashFlow(), "freeCashFlow");
        addIfNull(unavailable, metrics.revenueGrowthPercent(), "revenueGrowthPercent");
        addIfNull(unavailable, metrics.earningsGrowthPercent(), "earningsGrowthPercent");
        return List.copyOf(unavailable);
    }

    private void addIfNull(List<String> unavailable, BigDecimal value, String metric) {
        if (value == null) {
            unavailable.add(metric);
        }
    }

    private String describeReportingPeriod(LocalDate quarterEnd) {
        return quarterEnd != null
                ? "Trailing twelve months / most recent quarter ending " + quarterEnd
                : "Trailing twelve months; the provider did not state the period end date";
    }

    /** Yahoo expresses margins and returns as fractions; the model reads percentages. */
    private BigDecimal toPercent(BigDecimal fraction) {
        return fraction == null ? null : scale(fraction.multiply(HUNDRED));
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate toLocalDate(BigDecimal epochSeconds) {
        return epochSeconds == null
                ? null
                : Instant.ofEpochSecond(epochSeconds.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String companyName(YahooSymbolResolver.Resolution resolution, YahooQuoteSummaryResponse.Price price) {
        if (price != null && price.longName() != null) {
            return price.longName();
        }
        return resolution.meta().longName() != null ? resolution.meta().longName() : resolution.symbol();
    }
}
