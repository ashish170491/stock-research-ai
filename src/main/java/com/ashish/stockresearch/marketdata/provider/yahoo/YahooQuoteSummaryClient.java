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

import tools.jackson.databind.JsonNode;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fetches fundamentals from Yahoo's authenticated endpoints: the
 * {@code /v10/finance/quoteSummary} modules and the fundamentals time series.
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
        YahooQuoteSummaryResponse response = withCredentials(providerSymbol, credentials -> get(
                uriBuilder -> uriBuilder.path("/v10/finance/quoteSummary/{symbol}")
                        .queryParam("modules", modules)
                        .queryParam("crumb", credentials.crumb())
                        .build(providerSymbol),
                credentials, providerSymbol, YahooQuoteSummaryResponse.class));

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

    /**
     * Fetches statement line items from Yahoo's fundamentals time-series
     * endpoint, which - unlike quoteSummary - dates every value with its
     * period end and states its period type and currency.
     *
     * @param types e.g. "annualTotalRevenue", "annualNetIncome"
     * @return values per requested type, oldest first; a type Yahoo has no data for maps to an empty list
     */
    Map<String, List<TimeseriesValue>> fetchTimeseries(String providerSymbol, List<String> types,
                                                       LocalDate from, LocalDate to) {
        JsonNode body = withCredentials(providerSymbol, credentials -> get(
                uriBuilder -> uriBuilder.path("/ws/fundamentals-timeseries/v1/finance/timeseries/{symbol}")
                        .queryParam("type", String.join(",", types))
                        .queryParam("period1", from.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .queryParam("period2", to.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        .queryParam("crumb", credentials.crumb())
                        .build(providerSymbol),
                credentials, providerSymbol, JsonNode.class));

        JsonNode results = body == null ? null : body.path("timeseries").path("result");
        if (results == null || !results.isArray()) {
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_ERROR,
                    "Yahoo Finance returned an unreadable fundamentals time series for '%s'".formatted(providerSymbol));
        }
        Map<String, List<TimeseriesValue>> byType = new LinkedHashMap<>();
        types.forEach(type -> byType.put(type, new ArrayList<>()));
        for (JsonNode result : results) {
            JsonNode typeNode = result.path("meta").path("type").path(0);
            String type = typeNode.isString() ? typeNode.asString() : null;
            if (type == null || !byType.containsKey(type)) {
                continue;
            }
            for (JsonNode entry : result.path(type)) {
                TimeseriesValue value = toTimeseriesValue(entry);
                if (value != null) {
                    byType.get(type).add(value);
                }
            }
        }
        byType.values().forEach(values -> values.sort(Comparator.comparing(TimeseriesValue::asOfDate)));
        return byType;
    }

    /** One dated statement value. Entries missing a date or a number are dropped, never zero-filled. */
    record TimeseriesValue(LocalDate asOfDate, String periodType, YahooValue value, String currencyCode) {
    }

    private TimeseriesValue toTimeseriesValue(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return null;
        }
        JsonNode date = entry.path("asOfDate");
        YahooValue value = YahooValue.fromNode(entry.get("reportedValue"));
        if (!date.isString() || value == null) {
            return null;
        }
        JsonNode periodType = entry.path("periodType");
        JsonNode currency = entry.path("currencyCode");
        return new TimeseriesValue(LocalDate.parse(date.asString()),
                periodType.isString() ? periodType.asString() : null,
                value,
                currency.isString() ? currency.asString() : null);
    }

    /**
     * Runs a request with the cached credentials, and if Yahoo rejects the
     * crumb before its TTL expired, forces a fresh handshake and tries
     * exactly once more - a second failure is real.
     */
    private <T> T withCredentials(String providerSymbol, Function<YahooCrumbProvider.Credentials, T> request) {
        try {
            return request.apply(credentials());
        } catch (InvalidCrumbException ex) {
            log.info("Yahoo rejected the cached crumb for {}; renewing credentials", providerSymbol);
            crumbProvider.invalidate();
            try {
                return request.apply(credentials());
            } catch (InvalidCrumbException retryFailure) {
                throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                        ("Yahoo Finance rejected our session credentials, so fundamentals data could not be "
                                + "retrieved for '%s'. This is a provider-side restriction, not missing data.")
                                .formatted(providerSymbol));
            }
        }
    }

    private YahooCrumbProvider.Credentials credentials() {
        return crumbProvider.get()
                .orElseThrow(() -> new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                        "Could not authenticate with Yahoo Finance, so fundamentals data is currently "
                                + "unavailable. No figures could be retrieved - treat them as unknown, not zero."));
    }

    private <T> T get(Function<UriBuilder, URI> uri, YahooCrumbProvider.Credentials credentials,
                      String providerSymbol, Class<T> type) {
        try {
            return restClient.get()
                    .uri(uri)
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
                    .body(type);
        } catch (InvalidCrumbException | ResearchDataUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Covers connect/read timeouts and unparseable payloads alike.
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                    "Could not reach Yahoo Finance for '%s': %s".formatted(providerSymbol, ex.getMessage()), ex);
        }
    }

    /** Internal signal that the crumb needs renewing; never escapes {@link #fetch}. */
    private static class InvalidCrumbException extends RuntimeException {
        InvalidCrumbException() {
            super("Invalid crumb");
        }
    }
}
