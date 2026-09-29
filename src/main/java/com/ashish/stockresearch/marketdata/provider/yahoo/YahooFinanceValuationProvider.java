package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.ValuationDataProvider;
import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.model.CurrencyInfo;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportedValuation;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Valuation figures from Yahoo Finance's {@code summaryDetail}, {@code defaultKeyStatistics} and
 * {@code price} modules: the share price and the trailing P/E, price-to-book, dividend yield and
 * per-share figures measured against it.
 *
 * <p>Per-share figures are in the listing currency (rupees) even where the accounts are reported in
 * another currency. Checked against live data on 2026-09-27: INFY.NS reports its accounts in USD,
 * yet trailingEps is 77.52 and bookValue 227.86 - rupee amounts, which reproduce Yahoo's trailing P/E
 * (1000.20 / 77.52 = 12.90) and price-to-book (4.39) from the rupee price. A price quoted in any
 * currency other than rupees is refused rather than mixed with rupee per-share figures.
 */
@Component
@Profile("!mock")
class YahooFinanceValuationProvider implements ValuationDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceValuationProvider.class);
    static final String MODULES = "summaryDetail,defaultKeyStatistics,price";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");

    private final YahooSymbolResolver symbolResolver;
    private final YahooQuoteSummaryClient quoteSummaryClient;

    YahooFinanceValuationProvider(YahooSymbolResolver symbolResolver, YahooQuoteSummaryClient quoteSummaryClient) {
        this.symbolResolver = symbolResolver;
        this.quoteSummaryClient = quoteSummaryClient;
    }

    @Override
    @Cacheable(cacheNames = YahooCacheConfiguration.VALUATIONS, keyGenerator = YahooCacheConfiguration.SYMBOL_KEY)
    public ReportedValuation getReportedValuation(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));
        YahooQuoteSummaryResponse.Result result = quoteSummaryClient.fetch(resolution.providerSymbol(), MODULES);
        YahooQuoteSummaryResponse.Price price = result.price();
        if (price == null || price.regularMarketPrice() == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance published no share price for '%s', so no valuation can be given"
                            .formatted(resolution.providerSymbol()));
        }
        if (!"INR".equalsIgnoreCase(price.currency())) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "The share price of '%s' is quoted in %s; valuation is only given for rupee prices"
                            .formatted(resolution.providerSymbol(), price.currency()));
        }
        YahooQuoteSummaryResponse.SummaryDetail detail = result.summaryDetail();
        YahooQuoteSummaryResponse.DefaultKeyStatistics statistics = result.defaultKeyStatistics();
        LocalDate asOf = price.regularMarketTime() == null ? null
                : Instant.ofEpochSecond(price.regularMarketTime().longValue()).atZone(EXCHANGE_ZONE).toLocalDate();
        ReportingPeriod at = asOf != null ? ReportingPeriod.pointInTime(asOf) : ReportingPeriod.unknown();
        SourceInfo base = new SourceInfo(YahooFinanceFinancialDataProvider.SOURCE, SourceType.MARKET_DATA_PROVIDER,
                null, null, asOf);

        ReportedValuation valuation = new ReportedValuation(
                resolution.symbol(),
                resolution.exchange(),
                price.longName() != null ? price.longName() : resolution.symbol(),
                asOf,
                perShare("sharePrice", price.regularMarketPrice(), at, base, "price.regularMarketPrice"),
                perShare("trailingEps", statistics != null ? statistics.trailingEps() : null, at, base,
                        "defaultKeyStatistics.trailingEps"),
                perShare("bookValuePerShare", statistics != null ? statistics.bookValue() : null, at, base,
                        "defaultKeyStatistics.bookValue"),
                detail != null && detail.currency() != null && !"INR".equalsIgnoreCase(detail.currency())
                        ? FinancialDataPoint.unavailable("dividendPerShare", Unit.INR_PER_SHARE, at,
                                base.withField("summaryDetail.dividendRate"), ("Yahoo Finance quotes summaryDetail "
                                        + "in %s, so its dividend per share is not labelled as rupees")
                                        .formatted(detail.currency()))
                        : perShare("dividendPerShare", detail != null ? detail.dividendRate() : null, at, base,
                                "summaryDetail.dividendRate"),
                multiple("trailingPe", detail != null ? detail.trailingPE() : null, at, base,
                        "summaryDetail.trailingPE"),
                multiple("priceToBook", statistics != null ? statistics.priceToBook() : null, at, base,
                        "defaultKeyStatistics.priceToBook"),
                dividendYield(detail != null ? detail.dividendYield() : null, at, base),
                new DataProvenance(YahooFinanceFinancialDataProvider.SOURCE, SourceType.MARKET_DATA_PROVIDER,
                        DataFreshness.DELAYED, Instant.now(), null, asOf, null, note(asOf)));
        log.info("Retrieved Yahoo Finance valuation for {} as of {}", resolution.providerSymbol(), at.label());
        return valuation;
    }

    private static String note(LocalDate asOf) {
        return ("Point-in-time valuation: every figure is measured against the share price %s (exchange prices "
                + "are delayed by about 15 minutes) and changes with it. Per-share figures are in rupees, the listing "
                + "currency, even where the accounts are reported in another currency; each is confirmed by the "
                + "cross-check of its multiple against the rupee share price, and disputed with it if that fails. These are facts about the "
                + "price, not judgements: there is no fair value, price target or recommendation.")
                .formatted(asOf != null ? "on " + asOf : "at an unstated date");
    }

    private static FinancialDataPoint perShare(String metric, YahooValue raw, ReportingPeriod period,
                                               SourceInfo base, String field) {
        SourceInfo source = base.withField(field);
        if (raw == null) {
            return FinancialDataPoint.unavailable(metric, Unit.INR_PER_SHARE, period, source,
                    "Yahoo Finance did not publish " + field);
        }
        return YahooFieldSemantics.verify(raw, YahooFieldSemantics.PER_SHARE)
                .map(unit -> FinancialDataPoint.reported(metric, raw.raw(), unit, raw.raw().setScale(2,
                        RoundingMode.HALF_UP), Unit.INR_PER_SHARE, period, source)
                        .withCurrency(CurrencyInfo.unchanged("INR")))
                .orElseGet(() -> unverified(metric, raw, period, source, "a per-share amount"));
    }

    private static FinancialDataPoint multiple(String metric, YahooValue raw, ReportingPeriod period,
                                               SourceInfo base, String field) {
        SourceInfo source = base.withField(field);
        if (raw == null) {
            return FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, period, source,
                    "Yahoo Finance did not publish " + field);
        }
        return YahooFieldSemantics.verify(raw, YahooFieldSemantics.MULTIPLE)
                .map(unit -> FinancialDataPoint.reported(metric, raw.raw(), unit,
                        raw.raw().setScale(2, RoundingMode.HALF_UP), Unit.MULTIPLE, period, source))
                .orElseGet(() -> unverified(metric, raw, period, source, "a multiple"));
    }

    /** Yahoo sends the dividend yield as a fraction; its formatted value must confirm that per value. */
    private static FinancialDataPoint dividendYield(YahooValue raw, ReportingPeriod period, SourceInfo base) {
        String metric = "dividendYieldPercent";
        SourceInfo source = base.withField("summaryDetail.dividendYield");
        if (raw == null) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, period, source,
                    "Yahoo Finance published no dividend yield (summaryDetail.dividendYield)");
        }
        return YahooFieldSemantics.verify(raw, YahooFieldSemantics.RATIO)
                .map(unit -> FinancialDataPoint.reported(metric, raw.raw(), unit, unit == ProviderUnit.FRACTION
                        ? FinancialUnits.fractionToPercent(raw.raw()) : raw.raw().setScale(2, RoundingMode.HALF_UP),
                        Unit.PERCENT, period, source))
                .orElseGet(() -> unverified(metric, raw, period, source, "a fraction or a percentage"));
    }

    private static FinancialDataPoint unverified(String metric, YahooValue raw, ReportingPeriod period,
                                                 SourceInfo source, String meaning) {
        return FinancialDataPoint.semanticsUnverified(metric, raw.raw(), period, source,
                "Yahoo's formatted value %s does not confirm %s".formatted(
                        raw.fmt() == null ? "(none sent)" : "'" + raw.fmt() + "'", meaning));
    }
}
