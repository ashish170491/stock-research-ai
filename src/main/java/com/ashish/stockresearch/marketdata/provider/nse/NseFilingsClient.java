package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.StatementScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * NSE's two listings of a company's results filings, and the documents they link to.
 *
 * <ul>
 *   <li>{@code /api/corporates-financial-results?period=Annual} - the full-year results filings, up to FY24;</li>
 *   <li>{@code /api/integrated-filing-results} - SEBI's integrated filings, which replaced them from the
 *       quarter ending December 2024; paged, and read to the last page.</li>
 * </ul>
 *
 * A listing entry without an XBRL document (NSE lists "-" for filings before XBRL) is left out. A
 * document is downloaded only from NSE's archive host, so a listing can never send the application to
 * another site.
 */
class NseFilingsClient {

    private static final Logger log = LoggerFactory.getLogger(NseFilingsClient.class);
    private static final int PAGE_SIZE = 100;
    /** A company files five integrated filings a quarter at most; this many pages is decades of them. */
    private static final int MAX_PAGES = 20;
    private static final DateTimeFormatter DAY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter FILED = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy HH:mm[:ss]").toFormatter(Locale.ENGLISH);
    private static final Pattern DOCUMENT_PATH = Pattern.compile("/corporate/xbrl/[A-Za-z0-9_.-]+\\.xml");
    static final String INTEGRATED_FINANCIALS = "Integrated Filing- Financials";

    private final RestClient api;
    private final RestClient archive;
    private final String archiveHost;

    NseFilingsClient(RestClient api, RestClient archive, String archiveHost) {
        this.api = api;
        this.archive = archive;
        this.archiveHost = archiveHost;
    }

    List<NseFilingListing> annualResults(String symbol) {
        JsonNode body = get(() -> api.get().uri(uri -> uri.path("/api/corporates-financial-results")
                        .queryParam("index", "equities").queryParam("symbol", symbol).queryParam("period", "Annual")
                        .build()).retrieve().body(JsonNode.class), symbol, "annual results");
        List<NseFilingListing> listings = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return listings;
        }
        for (JsonNode entry : body) {
            LocalDate end = day(text(entry, "toDate"));
            String xbrl = text(entry, "xbrl");
            if (end == null || !isDocument(xbrl)) {
                continue;
            }
            listings.add(new NseFilingListing(symbol, end, scope(text(entry, "consolidated")),
                    "Audited".equalsIgnoreCase(text(entry, "audited")), filed(text(entry, "filingDate")), xbrl,
                    NseFilingListing.Format.RESULTS));
        }
        return listings;
    }

    /** Every integrated financials filing, from every page of the listing. */
    List<NseFilingListing> integratedFinancials(String symbol) {
        List<NseFilingListing> listings = new ArrayList<>();
        int seen = 0;
        for (int page = 1; ; page++) {
            int current = page;
            JsonNode body = get(() -> api.get().uri(uri -> uri.path("/api/integrated-filing-results")
                    .queryParam("index", "equities").queryParam("symbol", symbol).queryParam("size", PAGE_SIZE)
                    .queryParam("page", current).build()).retrieve().body(JsonNode.class), symbol, "integrated filings");
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                break;
            }
            for (JsonNode entry : data) {
                seen++;
                LocalDate end = day(text(entry, "qe_Date"));
                String xbrl = text(entry, "xbrl");
                if (!INTEGRATED_FINANCIALS.equals(text(entry, "type")) || end == null || !isDocument(xbrl)) {
                    continue;
                }
                LocalDateTime filedAt = filed(text(entry, "revised_Date") != null ? text(entry, "revised_Date")
                        : text(entry, "broadcast_Date"));
                listings.add(new NseFilingListing(symbol, end, scope(text(entry, "consolidated")),
                        "Audited".equalsIgnoreCase(text(entry, "audited")), filedAt, xbrl,
                        NseFilingListing.Format.INTEGRATED));
            }
            // Paged by the total NSE states, not by the page's length: NSE has served 20 entries a page
            // whatever size was asked for.
            JsonNode total = body.get("totalCount");
            if (total == null || !total.isNumber() || seen >= total.asInt()) {
                break;
            }
            if (page == MAX_PAGES) {
                log.warn("NSE's integrated filings listing for {} still had entries after {} pages; read {} of {}",
                        symbol, MAX_PAGES, seen, total.asInt());
                break;
            }
        }
        return listings;
    }

    /** The XBRL document at a listed link, which must be on NSE's archive host. */
    byte[] document(String url) {
        URI uri = documentUri(url);
        byte[] body = get(() -> archive.get().uri(uri).retrieve().body(byte[].class), url, "document");
        if (body == null || body.length == 0) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE, "NSE returned an empty document: " + url);
        }
        return body;
    }

    URI documentUri(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Not a document link: " + url, ex);
        }
        if (!"https".equals(uri.getScheme()) || !archiveHost.equals(uri.getHost()) || uri.getQuery() != null
                || uri.getPath() == null || !DOCUMENT_PATH.matcher(uri.getPath()).matches()) {
            throw new IllegalArgumentException("Refusing a document link that is not an XBRL file on %s: %s"
                    .formatted(archiveHost, url));
        }
        return uri;
    }

    private boolean isDocument(String xbrl) {
        if (xbrl == null || xbrl.isBlank() || xbrl.endsWith("/-")) {
            return false;
        }
        try {
            documentUri(xbrl);
            return true;
        } catch (IllegalArgumentException ex) {
            log.warn("NSE listed a document link that is refused: {}", ex.getMessage());
            return false;
        }
    }

    private static <T> T get(java.util.function.Supplier<T> call, String what, String kind) {
        try {
            return call.get();
        } catch (RestClientException ex) {
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE,
                    "NSE %s for %s could not be retrieved: %s".formatted(kind, what, ex.getMessage()), ex);
        }
    }

    private static StatementScope scope(String consolidated) {
        return consolidated != null && consolidated.strip().equalsIgnoreCase("Consolidated")
                ? StatementScope.CONSOLIDATED : StatementScope.STANDALONE;
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() || value.asString().isBlank() ? null : value.asString().strip();
    }

    private static LocalDate day(String text) {
        try {
            return text == null ? null : LocalDate.parse(text, DAY);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDateTime filed(String text) {
        try {
            return text == null ? null : LocalDateTime.parse(text, FILED);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
