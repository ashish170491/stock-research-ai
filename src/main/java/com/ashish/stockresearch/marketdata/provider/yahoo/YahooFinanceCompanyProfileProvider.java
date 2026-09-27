package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.CompanyProfileProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.calc.FinancialUnits.ProviderScale;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Company profile backed by Yahoo Finance's {@code assetProfile},
 * {@code price} and {@code defaultKeyStatistics} modules.
 *
 * Market capitalisation arrives in whole rupees and is normalised to
 * {@link Unit#INR_CRORE} here, dated by the last trade time. It is then
 * cross-checked against share price x shares outstanding, so a unit error
 * (the 10x class of bug) is caught and flagged instead of reaching a report.
 *
 * Descriptive fields have no as-of date - Yahoo does not publish when the
 * business summary was last revised, and inventing one would be worse than
 * admitting it.
 */
@Component
@Profile("!mock")
class YahooFinanceCompanyProfileProvider implements CompanyProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceCompanyProfileProvider.class);
    private static final String MODULES = "assetProfile,price,defaultKeyStatistics";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");

    private final YahooSymbolResolver symbolResolver;
    private final YahooQuoteSummaryClient quoteSummaryClient;
    private final FinancialDataValidator validator;

    YahooFinanceCompanyProfileProvider(YahooSymbolResolver symbolResolver,
                                       YahooQuoteSummaryClient quoteSummaryClient,
                                       FinancialDataValidator validator) {
        this.symbolResolver = symbolResolver;
        this.quoteSummaryClient = quoteSummaryClient;
        this.validator = validator;
    }

    @Override
    public CompanyProfile getCompanyProfile(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));

        YahooQuoteSummaryResponse.Result result = quoteSummaryClient.fetch(resolution.providerSymbol(), MODULES);
        YahooQuoteSummaryResponse.AssetProfile profile = result.assetProfile();
        YahooQuoteSummaryResponse.Price price = result.price();
        YahooQuoteSummaryResponse.DefaultKeyStatistics statistics = result.defaultKeyStatistics();

        if (profile == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance publishes no company profile for '%s'".formatted(resolution.providerSymbol()));
        }

        LocalDate asOf = price != null && price.regularMarketTime() != null
                ? Instant.ofEpochSecond(price.regularMarketTime().longValue()).atZone(EXCHANGE_ZONE).toLocalDate()
                : null;
        SourceInfo source = new SourceInfo(YahooFinanceFinancialDataProvider.SOURCE,
                SourceType.MARKET_DATA_PROVIDER, null, null, asOf);
        FinancialDataPoint marketCap = marketCap(price, asOf, source);
        FinancialDataPoint shares = sharesOutstanding(statistics, source);
        YahooValue lastPrice = price != null ? price.regularMarketPrice() : null;

        List<DataQualityIssue> issues = new ArrayList<>();
        if (marketCap.available() && marketCap.unit() == Unit.INR_CRORE && shares.available()
                && YahooFieldSemantics.verify(lastPrice, YahooFieldSemantics.PER_SHARE).isPresent()) {
            validator.marketCapInconsistency(marketCap.value(), lastPrice.raw(), shares.value())
                    .ifPresent(issues::add);
        }

        log.info("Retrieved Yahoo Finance company profile for {} (market cap {})",
                resolution.providerSymbol(), marketCap.display());
        return new CompanyProfile(
                resolution.symbol(),
                resolution.exchange(),
                companyName(resolution, price),
                profile.sector(),
                profile.industry(),
                profile.country(),
                profile.website(),
                profile.fullTimeEmployees(),
                marketCap,
                shares,
                profile.longBusinessSummary(),
                List.copyOf(issues),
                new DataProvenance(
                        YahooFinanceFinancialDataProvider.SOURCE,
                        SourceType.MARKET_DATA_PROVIDER,
                        DataFreshness.REFERENCE,
                        Instant.now(),
                        null,
                        asOf,
                        null,
                        "Source: Yahoo Finance (unofficial, undocumented endpoints). Descriptive company data; "
                                + "Yahoo does not publish when it was last revised. "
                                + "Market cap is normalised to crore and dated by the last trade; quote its "
                                + "'display' field rather than converting it. The business summary is the only "
                                + "source for qualitative statements about the company."));
    }

    private FinancialDataPoint marketCap(YahooQuoteSummaryResponse.Price price, LocalDate asOf, SourceInfo base) {
        SourceInfo source = base.withField("price.marketCap");
        ReportingPeriod period = ReportingPeriod.pointInTime(asOf);
        YahooValue raw = price != null ? price.marketCap() : null;
        if (raw == null) {
            return FinancialDataPoint.unavailable("marketCap", Unit.INR_CRORE, period, source,
                    "Yahoo Finance did not publish a market capitalisation");
        }
        if (YahooFieldSemantics.verify(raw, YahooFieldSemantics.AMOUNT).isEmpty()) {
            return FinancialDataPoint.semanticsUnverified("marketCap", raw.raw(), period, source,
                    "Yahoo's formatted value does not confirm an amount in whole currency units");
        }
        return FinancialUnits.normalizeAmount(raw.raw(), ProviderScale.ONES, price.currency())
                .map(normalized -> FinancialDataPoint.reported("marketCap", raw.raw(), ProviderUnit.WHOLE_CURRENCY_UNITS,
                        normalized.value(), normalized.unit(), period, source))
                .orElseGet(() -> FinancialDataPoint.unavailable("marketCap", null, period, source,
                        "Market cap currency '%s' is unknown or unsupported, so it cannot be labelled"
                                .formatted(price.currency())));
    }

    /** Yahoo gives no as-of date for the share count, so its period is UNKNOWN rather than assumed current. */
    private FinancialDataPoint sharesOutstanding(YahooQuoteSummaryResponse.DefaultKeyStatistics statistics,
                                                 SourceInfo base) {
        SourceInfo source = base.withDates(null, null).withField("defaultKeyStatistics.sharesOutstanding");
        YahooValue raw = statistics != null ? statistics.sharesOutstanding() : null;
        if (raw == null) {
            return FinancialDataPoint.unavailable("sharesOutstanding", Unit.SHARES, ReportingPeriod.unknown(), source,
                    "Yahoo Finance did not publish shares outstanding");
        }
        if (YahooFieldSemantics.verify(raw, YahooFieldSemantics.SHARES).isEmpty()) {
            return FinancialDataPoint.semanticsUnverified("sharesOutstanding", raw.raw(), ReportingPeriod.unknown(),
                    source, "Yahoo's formatted value does not confirm a share count");
        }
        return FinancialDataPoint.reported("sharesOutstanding", raw.raw(), ProviderUnit.SHARE_COUNT, raw.raw(),
                Unit.SHARES, ReportingPeriod.unknown(), source);
    }

    private String companyName(YahooSymbolResolver.Resolution resolution, YahooQuoteSummaryResponse.Price price) {
        if (price != null && price.longName() != null) {
            return price.longName();
        }
        if (price != null && price.shortName() != null) {
            return price.shortName();
        }
        return resolution.meta() != null && resolution.meta().longName() != null
                ? resolution.meta().longName() : resolution.symbol();
    }
}
