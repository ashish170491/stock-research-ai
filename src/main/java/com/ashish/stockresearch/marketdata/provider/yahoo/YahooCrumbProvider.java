package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Obtains and caches the session cookie + "crumb" pair that Yahoo's
 * {@code /v10/finance/quoteSummary} endpoint requires. Without it that
 * endpoint answers {@code 401 Unauthorized / Invalid Crumb} for every
 * request - which is why the fundamentals providers need this and the plain
 * chart endpoint does not.
 *
 * The handshake is: request a Yahoo host that sets a session cookie, then
 * exchange that cookie for a crumb token. Both are then sent on every
 * quoteSummary call.
 *
 * Credentials are cached because the handshake costs two extra round trips.
 * They are refreshed when the TTL expires, and {@link #invalidate()} lets a
 * caller force a refresh the moment Yahoo rejects a crumb - tokens can be
 * revoked before the TTL is up.
 *
 * Nothing here is a secret of the user's: the cookie is an anonymous
 * session issued to this process, and it is never logged.
 */
@Component
@Profile("!mock")
class YahooCrumbProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooCrumbProvider.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RestClient cookieRestClient;
    private final RestClient apiRestClient;

    /** Guarded by {@code this}; refreshed lazily on first use and after invalidation. */
    private Credentials cached;

    YahooCrumbProvider(RestClient yahooCookieRestClient, RestClient yahooFinanceRestClient) {
        this.cookieRestClient = yahooCookieRestClient;
        this.apiRestClient = yahooFinanceRestClient;
    }

    record Credentials(String cookie, String crumb, Instant expiresAt) {
    }

    /**
     * @return the current credentials, performing the handshake if needed, or
     *         empty when Yahoo will not issue them (in which case the caller must
     *         report the capability as unavailable rather than guess).
     */
    synchronized Optional<Credentials> get() {
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return Optional.of(cached);
        }
        Optional<Credentials> fresh = handshake();
        fresh.ifPresent(credentials -> cached = credentials);
        return fresh;
    }

    /** Drops the cached pair so the next {@link #get()} performs a fresh handshake. */
    synchronized void invalidate() {
        cached = null;
    }

    private Optional<Credentials> handshake() {
        Optional<String> cookie = fetchSessionCookie();
        if (cookie.isEmpty()) {
            log.warn("Yahoo Finance did not issue a session cookie - fundamentals endpoints will be unavailable");
            return Optional.empty();
        }

        Optional<String> crumb = fetchCrumb(cookie.get());
        if (crumb.isEmpty()) {
            log.warn("Yahoo Finance did not issue a crumb - fundamentals endpoints will be unavailable");
            return Optional.empty();
        }

        log.info("Obtained Yahoo Finance session credentials for fundamentals endpoints");
        return Optional.of(new Credentials(cookie.get(), crumb.get(), Instant.now().plus(TTL)));
    }

    /**
     * The cookie host answers 404 by design - the cookie arrives in the
     * response headers regardless, so every status is accepted here.
     */
    private Optional<String> fetchSessionCookie() {
        try {
            ResponseEntity<Void> response = cookieRestClient.get()
                    .retrieve()
                    .onStatus(status -> true, (request, res) -> { })
                    .toBodilessEntity();

            List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies == null || setCookies.isEmpty()) {
                return Optional.empty();
            }
            String cookieHeader = setCookies.stream()
                    .map(this::nameValuePair)
                    .filter(pair -> !pair.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            return cookieHeader.isBlank() ? Optional.empty() : Optional.of(cookieHeader);
        } catch (RestClientException ex) {
            log.warn("Yahoo Finance cookie handshake failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Keeps only the {@code name=value} part, dropping Expires/Domain/Path attributes. */
    private String nameValuePair(String setCookieValue) {
        int semicolon = setCookieValue.indexOf(';');
        return (semicolon > 0 ? setCookieValue.substring(0, semicolon) : setCookieValue).strip();
    }

    private Optional<String> fetchCrumb(String cookie) {
        try {
            String crumb = apiRestClient.get()
                    .uri("/v1/test/getcrumb")
                    .header(HttpHeaders.COOKIE, cookie)
                    .retrieve()
                    .body(String.class);
            // Yahoo answers 200 with an empty body when the cookie is not accepted.
            return crumb == null || crumb.isBlank() ? Optional.empty() : Optional.of(crumb.strip());
        } catch (RestClientException ex) {
            log.warn("Yahoo Finance crumb request failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
