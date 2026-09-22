package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.MarketDataProvider;
import com.ashish.stockresearch.marketdata.MarketDataUnavailableException;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Real, near real-time market-data provider backed by Yahoo Finance's
 * unofficial endpoints. No API key required.
 *
 * Resolution is a best-effort chain, each stage cheaper/more reliable than
 * the next:
 * 1. Try the input directly as an NSE symbol, then as a BSE symbol
 *    (/v8/finance/chart). This handles exact trading symbols (e.g. "LT",
 *    "RELIANCE") without depending on Yahoo's fuzzy search at all - which
 *    matters because that search doesn't reliably rank the correct Indian
 *    listing for every query (e.g. a bare "LT" or "Larsen and Toubro" both
 *    fail to surface LT.NS, even though it exists).
 * 2. Fall back to /v1/finance/search to resolve a company name (e.g.
 *    "Reliance Industries") to a concrete NSE- or BSE-listed equity symbol.
 * 3. Last resort: ask the LLM for a candidate ticker (handles names whose
 *    mnemonic isn't a simple concatenation, e.g. "Bharti Airtel" ->
 *    BHARTIARTL) and verify that guess the same way as any other candidate
 *    - it is never trusted without a real chart lookup succeeding.
 *
 * These endpoints are not officially documented by Yahoo and could change
 * or be rate-limited without notice - that risk is why
 * {@link MarketDataUnavailableException} is used liberally here instead of
 * ever fabricating a price.
 */
@Component
@Profile("!mock")
public class YahooFinanceMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceMarketDataProvider.class);

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
    private final LlmSymbolResolver llmSymbolResolver;

    public YahooFinanceMarketDataProvider(RestClient yahooFinanceRestClient, LlmSymbolResolver llmSymbolResolver) {
        this.restClient = yahooFinanceRestClient;
        this.llmSymbolResolver = llmSymbolResolver;
    }

    @Override
    public StockQuote getQuote(String symbolOrName) {
        Optional<StockQuote> quote = tryDirectGuess(symbolOrName)
                .or(() -> trySearch(symbolOrName))
                .or(() -> tryLlmGuess(symbolOrName));

        return quote.orElseThrow(() -> new MarketDataUnavailableException(
                "No NSE or BSE listed stock found matching '" + symbolOrName + "'"));
    }

    private Optional<StockQuote> tryDirectGuess(String symbolOrName) {
        String candidate = directCandidate(symbolOrName);
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        return tryQuote(candidate + ".NS", "NSE").or(() -> tryQuote(candidate + ".BO", "BSE"));
    }

    private Optional<StockQuote> trySearch(String symbolOrName) {
        return resolveViaSearch(symbolOrName)
                .flatMap(match -> tryQuote(match.symbol(), exchangeLabelFor(match.exchange())));
    }

    private Optional<StockQuote> tryLlmGuess(String symbolOrName) {
        return llmSymbolResolver.resolveTickerGuess(symbolOrName)
                .flatMap(guess -> tryQuote(guess + ".NS", "NSE").or(() -> tryQuote(guess + ".BO", "BSE")));
    }

    /** Strips noise words and returns what's left, uppercased and joined with no separator. */
    private String directCandidate(String symbolOrName) {
        return String.join("", significantWords(symbolOrName));
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
     * Fetches a chart quote for a concrete provider symbol. Returns empty
     * (rather than throwing) on any failure so callers can try the next
     * candidate in the resolution chain.
     */
    private Optional<StockQuote> tryQuote(String providerSymbol, String exchangeLabel) {
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

        try {
            Instant timestamp = meta.regularMarketTimeEpochSeconds() != null
                    ? Instant.ofEpochSecond(meta.regularMarketTimeEpochSeconds())
                    : Instant.now();
            String currency = meta.currency() != null ? meta.currency() : "INR";
            log.info("Retrieved Yahoo Finance quote for {} -> price={}", providerSymbol, meta.price());
            return Optional.of(new StockQuote(
                    stripExchangeSuffix(providerSymbol),
                    exchangeLabel,
                    meta.price(),
                    currency,
                    timestamp,
                    false,
                    "Yahoo Finance (near real-time, unofficial/undocumented endpoint)"
            ));
        } catch (RuntimeException ex) {
            log.debug("Malformed chart response for {}: {}", providerSymbol, ex.getMessage());
            return Optional.empty();
        }
    }

    private String stripExchangeSuffix(String providerSymbol) {
        int dotIndex = providerSymbol.indexOf('.');
        return dotIndex > 0 ? providerSymbol.substring(0, dotIndex) : providerSymbol;
    }
}
