package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a free-form stock symbol or company name into a concrete,
 * exchange-qualified Yahoo symbol (e.g. "TCS" -> "TCS.NS").
 *
 * Extracted so every research provider - quotes, profile, financials,
 * history - resolves symbols identically instead of each reimplementing the
 * chain.
 *
 * Resolution is best-effort, each stage cheaper/more reliable than the next:
 * <ol>
 *   <li>Look the input up in NSE's own equity list ({@link NseSymbolDirectory}): an exact symbol,
 *       or a deterministic company-name match. Offline, and it resolves names whose ticker is not a
 *       concatenation of the name (e.g. "Bharti Airtel" -> BHARTIARTL) without a model.</li>
 *   <li>Try the input directly as an NSE symbol, then as a BSE symbol
 *       (/v8/finance/chart). This handles exact trading symbols (e.g. "LT",
 *       "RELIANCE") without depending on Yahoo's fuzzy search at all - which
 *       matters because that search doesn't reliably rank the correct Indian
 *       listing for every query (e.g. a bare "LT" or "Larsen and Toubro" both
 *       fail to surface LT.NS, even though it exists).</li>
 *   <li>Fall back to /v1/finance/search to resolve a company name (e.g.
 *       "Reliance Industries") to a concrete NSE- or BSE-listed equity.</li>
 *   <li>Last resort: ask the LLM for a candidate ticker (handles names whose
 *       mnemonic isn't a simple concatenation, e.g. "Bharti Airtel" ->
 *       BHARTIARTL).</li>
 * </ol>
 *
 * Every candidate - including the LLM's - is confirmed by a real chart
 * lookup before being returned, so a hallucinated ticker can never escape
 * this class. The confirming response is handed back in
 * {@link Resolution#meta()} so callers that need the current price do not
 * have to fetch it twice.
 */
@Component
@Profile("!mock")
class YahooSymbolResolver {

    private static final Logger log = LoggerFactory.getLogger(YahooSymbolResolver.class);

    /** Yahoo's exchange codes for NSE and BSE, in resolution preference order. */
    private static final List<String> PREFERRED_EXCHANGES = List.of("NSI", "BSE");

    /**
     * Corporate-suffix and connector words that add noise to both a direct
     * symbol guess and Yahoo's fuzzy search ranking (e.g. "Larsen and
     * Toubro" only surfaces its NSE listing once "and" is dropped).
     */
    private static final Set<String> NOISE_WORDS = Set.of(
            "AND", "THE", "LIMITED", "LTD", "PVT", "PRIVATE", "COMPANY", "CO", "CORP", "CORPORATION", "INC", "PLC");

    private final RestClient restClient;
    private final NseSymbolDirectory nseSymbolDirectory;
    private final LlmSymbolResolver llmSymbolResolver;

    YahooSymbolResolver(RestClient yahooFinanceRestClient, NseSymbolDirectory nseSymbolDirectory,
                        LlmSymbolResolver llmSymbolResolver) {
        this.restClient = yahooFinanceRestClient;
        this.nseSymbolDirectory = nseSymbolDirectory;
        this.llmSymbolResolver = llmSymbolResolver;
    }

    /**
     * A confirmed listing.
     *
     * @param providerSymbol exchange-qualified Yahoo symbol, e.g. "TCS.NS"
     * @param symbol         plain trading symbol without the suffix, e.g. "TCS"
     * @param exchange       "NSE" or "BSE"
     * @param meta           the chart metadata that confirmed the symbol exists
     */
    record Resolution(String providerSymbol, String symbol, String exchange, YahooChartResponse.Meta meta) {
    }

    /**
     * The listing a symbol or company name refers to, cached for a week: listings rarely change,
     * and the lookup can cost several Yahoo calls and a model call. The {@link Resolution#meta()}
     * is from the first lookup, so read names from it, never prices; a quote uses
     * {@link #resolveWithLatestQuote}. A name that resolves to nothing is not cached.
     */
    @Cacheable(cacheNames = YahooCacheConfiguration.SYMBOL_RESOLUTION, keyGenerator = YahooCacheConfiguration.SYMBOL_KEY,
            unless = "#result == null")
    public Optional<Resolution> resolve(String symbolOrName) {
        return lookup(symbolOrName);
    }

    /** The same lookup, uncached, so the chart metadata carries the latest price. */
    public Optional<Resolution> resolveWithLatestQuote(String symbolOrName) {
        return lookup(symbolOrName);
    }

    private Optional<Resolution> lookup(String symbolOrName) {
        return tryDirectory(symbolOrName)
                .or(() -> tryDirectGuess(symbolOrName))
                .or(() -> trySearch(symbolOrName))
                .or(() -> tryLlmGuess(symbolOrName));
    }

    /** A directory match is still confirmed on NSE: the bundled list is a snapshot and may be stale. */
    private Optional<Resolution> tryDirectory(String symbolOrName) {
        return nseSymbolDirectory.resolve(symbolOrName)
                .flatMap(listing -> confirm(listing.symbol() + ".NS", "NSE"));
    }

    private Optional<Resolution> tryDirectGuess(String symbolOrName) {
        String candidate = String.join("", significantWords(symbolOrName));
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        return confirmOnEitherExchange(candidate);
    }

    private Optional<Resolution> trySearch(String symbolOrName) {
        return resolveViaSearch(symbolOrName)
                .flatMap(match -> confirm(match.symbol(), exchangeLabelFor(match.exchange())));
    }

    private Optional<Resolution> tryLlmGuess(String symbolOrName) {
        return llmSymbolResolver.resolveTickerGuess(symbolOrName)
                .flatMap(this::confirmOnEitherExchange);
    }

    private Optional<Resolution> confirmOnEitherExchange(String bareSymbol) {
        return confirm(bareSymbol + ".NS", "NSE").or(() -> confirm(bareSymbol + ".BO", "BSE"));
    }

    /** Strips noise words for a cleaner fuzzy-search query; falls back to the raw input if nothing is left. */
    private String searchQuery(String symbolOrName) {
        List<String> words = significantWords(symbolOrName);
        return words.isEmpty() ? symbolOrName.strip() : String.join(" ", words);
    }

    private List<String> significantWords(String symbolOrName) {
        return Arrays.stream(symbolOrName.strip().toUpperCase().split("[^A-Z0-9]+"))
                .filter(word -> !word.isBlank() && !NOISE_WORDS.contains(word))
                .toList();
    }

    private String exchangeLabelFor(String yahooExchangeCode) {
        return "NSI".equalsIgnoreCase(yahooExchangeCode) ? "NSE" : "BSE";
    }

    private Optional<YahooSearchResponse.Quote> resolveViaSearch(String symbolOrName) {
        String query = searchQuery(symbolOrName);
        YahooSearchResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/finance/search")
                            .queryParam("q", query)
                            .queryParam("quotesCount", 10)
                            .queryParam("newsCount", 0)
                            .build())
                    .retrieve()
                    .body(YahooSearchResponse.class);
        } catch (RestClientException ex) {
            log.debug("Search lookup failed for '{}': {}", symbolOrName, ex.getMessage());
            return Optional.empty();
        }

        List<YahooSearchResponse.Quote> quotes = response != null ? response.quotes() : null;
        if (quotes == null || quotes.isEmpty()) {
            return Optional.empty();
        }

        for (String exchange : PREFERRED_EXCHANGES) {
            Optional<YahooSearchResponse.Quote> match = quotes.stream()
                    .filter(q -> "EQUITY".equalsIgnoreCase(q.quoteType()))
                    .filter(q -> exchange.equalsIgnoreCase(q.exchange()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * Confirms a concrete provider symbol really trades by fetching a
     * one-day chart. Returns empty (rather than throwing) on any failure so
     * callers can move on to the next candidate in the chain.
     */
    private Optional<Resolution> confirm(String providerSymbol, String exchangeLabel) {
        YahooChartResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v8/finance/chart/{symbol}")
                            .queryParam("interval", "1d")
                            .queryParam("range", "1d")
                            .build(providerSymbol))
                    .retrieve()
                    .body(YahooChartResponse.class);
        } catch (RestClientException ex) {
            log.debug("Chart lookup failed for {}: {}", providerSymbol, ex.getMessage());
            return Optional.empty();
        }

        if (response == null || response.chart() == null || response.chart().error() != null) {
            return Optional.empty();
        }

        List<YahooChartResponse.Result> results = response.chart().result();
        if (results == null || results.isEmpty() || results.get(0).meta() == null) {
            return Optional.empty();
        }

        YahooChartResponse.Meta meta = results.get(0).meta();
        if (meta.price() == null) {
            return Optional.empty();
        }

        return Optional.of(new Resolution(providerSymbol, stripExchangeSuffix(providerSymbol), exchangeLabel, meta));
    }

    private String stripExchangeSuffix(String providerSymbol) {
        int dotIndex = providerSymbol.indexOf('.');
        return dotIndex > 0 ? providerSymbol.substring(0, dotIndex) : providerSymbol;
    }
}
