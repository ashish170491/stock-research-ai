package com.ashish.stockresearch.marketdata.provider.nse;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * {@code app.market-data.nse-filings}: where NSE's results filings are listed and archived, and how gently
 * they are fetched. NSE's website endpoints are unofficial, so requests are spaced and every document is
 * kept once fetched: a filing never changes, and a revision is a new document.
 *
 * @param apiBaseUrl        where the filing listings are served
 * @param archiveHost       the only host documents are downloaded from; any other link is refused
 * @param referer           the page the listings are requested from, as a browser would
 * @param documentDirectory where fetched documents are kept
 * @param listingTtl        how long a company's listings are reused before NSE is asked again
 */
@ConfigurationProperties("app.market-data.nse-filings")
public record NseFilingsProperties(
        String apiBaseUrl,
        String archiveHost,
        String userAgent,
        String referer,
        long timeoutMs,
        double requestsPerSecond,
        Path documentDirectory,
        Duration listingTtl
) {

    public NseFilingsProperties {
        apiBaseUrl = apiBaseUrl == null ? "https://www.nseindia.com" : apiBaseUrl;
        archiveHost = archiveHost == null ? "nsearchives.nseindia.com" : archiveHost;
        timeoutMs = timeoutMs <= 0 ? 20_000 : timeoutMs;
        documentDirectory = documentDirectory == null ? Path.of("data", "nse-filings") : documentDirectory;
        listingTtl = listingTtl == null ? Duration.ofHours(12) : listingTtl;
    }
}
