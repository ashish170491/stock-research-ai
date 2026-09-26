package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Fetches fundamentals modules from Yahoo's authenticated
 * {@code /v10/finance/quoteSummary} endpoint.
 *
 * Centralises three things the fundamentals providers would otherwise each
 * repeat: attaching the cookie/crumb credentials, retrying once when Yahoo
 * rejects a crumb that has been revoked early, and translating every
 * transport failure into a {@link ResearchDataUnavailableException} with an
 * accurate status - so a timeout is never reported to the model the same way
 * as "this company has no financials".
 */
@Component
@Profile("!mock")
class YahooQuoteSummaryClient {

    private static final Logger log = LoggerFactory.getLogger(YahooQuoteSummaryClient.class);

    private final RestClient restClient;
    private final YahooCrumbProvider crumbProvider;

    YahooQuoteSummaryClient(RestClient yahooFinanceRestClient, YahooCrumbProvider crumbProvider) {
        this.restClient = yahooFinanceRestClient;
        this.crumbProvider = crumbProvider;
    }

    /**
     * @param providerSymbol exchange-qualified Yahoo symbol, e.g. "TCS.NS"
     * @param modules        quoteSummary module names, e.g. "assetProfile,price"
     * @throws ResearchDataUnavailableException when the data cannot be retrieved
     */
    YahooQuoteSummaryResponse.Result fetch(String providerSymbol, String modules) {
        try {
            return attempt(providerSymbol, modules);
        } catch (InvalidCrumbException ex) {
            // The crumb was rejected before its TTL expired. Force a fresh
            // handshake and try exactly once more - a second failure is real.
            log.info("Yahoo rejected the cached crumb for {}; renewing credentials", providerSymbol);
            crumbProvider.invalidate();
            try {
                return attempt(providerSymbol, modules);
            } catch (InvalidCrumbException retryFailure) {
                throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                        ("Yahoo Finance rejected our session credentials, so fundamentals data could not be "
                                + "retrieved for '%s'. This is a provider-side restriction, not missing data.")
                                .formatted(providerSymbol));
            }
        }
    }

    private YahooQuoteSummaryResponse.Result attempt(String providerSymbol, String modules) {
        YahooCrumbProvider.Credentials credentials = crumbProvider.get()
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                        "Could not authenticate with Yahoo Finance, so fundamentals data is currently "
                                + "unavailable. No figures could be retrieved - treat them as unknown, not zero."));

        YahooQuoteSummaryResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v10/finance/quoteSummary/{symbol}")
                            .queryParam("modules", modules)
                            .queryParam("crumb", credentials.crumb())
                            .build(providerSymbol))
                    .header(HttpHeaders.COOKIE, credentials.cookie())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> {
                        if (res.getStatusCode().value() == 401) {
                            throw new InvalidCrumbException();
                        }
                        throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                                "Yahoo Finance returned HTTP %d for '%s'".formatted(
                                        res.getStatusCode().value(), providerSymbol));
                    })
                    .body(YahooQuoteSummaryResponse.class);
        } catch (InvalidCrumbException | ResearchDataUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Covers connect/read timeouts and unparseable payloads alike.
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                    "Could not reach Yahoo Finance for '%s': %s".formatted(providerSymbol, ex.getMessage()), ex);
        }

        if (response == null || response.quoteSummary() == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_ERROR,
                    "Yahoo Finance returned an unreadable response for '%s'".formatted(providerSymbol));
        }
        if (response.quoteSummary().error() != null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance has no '%s' data for '%s': %s".formatted(
                            modules, providerSymbol, response.quoteSummary().error().description()));
        }

        List<YahooQuoteSummaryResponse.Result> results = response.quoteSummary().result();
        if (results == null || results.isEmpty() || results.get(0) == null) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Yahoo Finance publishes no '%s' data for '%s'".formatted(modules, providerSymbol));
        }
        return results.get(0);
    }

    /** Internal signal that the crumb needs renewing; never escapes {@link #fetch}. */
    private static class InvalidCrumbException extends RuntimeException {
        InvalidCrumbException() {
            super("Invalid crumb");
        }
    }
}
