package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.CompanyProfileProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Company profile backed by Yahoo Finance's {@code assetProfile} and
 * {@code price} modules.
 *
 * Profile data is descriptive and slow-changing, so it is tagged
 * {@link DataFreshness#REFERENCE} with no as-of date - Yahoo does not
 * publish when the business summary was last revised, and inventing one
 * would be worse than admitting it.
 */
@Component
@Profile("!mock")
class YahooFinanceCompanyProfileProvider implements CompanyProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceCompanyProfileProvider.class);
    private static final String MODULES = "assetProfile,price";

    private final YahooSymbolResolver symbolResolver;
    private final YahooQuoteSummaryClient quoteSummaryClient;

    YahooFinanceCompanyProfileProvider(YahooSymbolResolver symbolResolver,
                                       YahooQuoteSummaryClient quoteSummaryClient) {
        this.symbolResolver = symbolResolver;
        this.quoteSummaryClient = quoteSummaryClient;
    }

    @Override
    public CompanyProfile getCompanyProfile(String symbolOrName) {
        YahooSymbolResolver.Resolution resolution = symbolResolver.resolve(symbolOrName)
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND,
                        "No NSE or BSE listed company found matching '%s'".formatted(symbolOrName)));

        YahooQuoteSummaryResponse.Result result =
                quoteSummaryClient.fetch(resolution.providerSymbol(), MODULES);
        YahooQuoteSummaryResponse.AssetProfile profile = result.assetProfile();
        YahooQuoteSummaryResponse.Price price = result.price();

        if (profile == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance publishes no company profile for '%s'".formatted(resolution.providerSymbol()));
        }

        log.info("Retrieved Yahoo Finance company profile for {}", resolution.providerSymbol());
        return new CompanyProfile(
                resolution.symbol(),
                resolution.exchange(),
                companyName(resolution, price),
                profile.sector(),
                profile.industry(),
                profile.country(),
                profile.website(),
                profile.fullTimeEmployees(),
                price != null ? price.marketCap() : null,
                price != null ? price.currency() : null,
                profile.longBusinessSummary(),
                new DataProvenance(
                        "Yahoo Finance (unofficial/undocumented endpoint)",
                        DataFreshness.REFERENCE,
                        Instant.now(),
                        null,
                        "Descriptive company data; Yahoo does not publish when it was last revised. "
                                + "Market capitalisation moves with the share price and is current as of retrieval."));
    }

    private String companyName(YahooSymbolResolver.Resolution resolution, YahooQuoteSummaryResponse.Price price) {
        if (price != null && price.longName() != null) {
            return price.longName();
        }
        if (price != null && price.shortName() != null) {
            return price.shortName();
        }
        return resolution.meta().longName() != null ? resolution.meta().longName() : resolution.symbol();
    }
}
