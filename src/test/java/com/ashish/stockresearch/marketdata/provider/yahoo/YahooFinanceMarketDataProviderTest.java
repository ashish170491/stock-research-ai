package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.MarketDataUnavailableException;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Uses MockRestServiceServer - no real Yahoo Finance call is made, and the
 * LLM symbol resolver is a plain Mockito mock so no Ollama call is made
 * either.
 */
class YahooFinanceMarketDataProviderTest {

    private static final String BASE_URL = "https://query1.finance.yahoo.com";
    private static final String NOT_FOUND_CHART = """
            { "chart": { "result": null, "error": { "code": "Not Found", "description": "No data found" } } }
            """;

    private MockRestServiceServer server;
    private LlmSymbolResolver llmSymbolResolver;
    private YahooFinanceMarketDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        llmSymbolResolver = mock(LlmSymbolResolver.class);
        // An empty directory, so these tests exercise the stages after it.
        NseSymbolDirectory noListings = new NseSymbolDirectory(new ByteArrayResource(
                "SYMBOL,NAME OF COMPANY\n".getBytes(StandardCharsets.UTF_8)));
        provider = new YahooFinanceMarketDataProvider(
                new YahooSymbolResolver(builder.build(), noListings, llmSymbolResolver));
    }

    @Test
    void resolvesCompanyNamesFromTheNseListBeforeGuessingOrSearching() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer strict = MockRestServiceServer.bindTo(builder).build();
        YahooFinanceMarketDataProvider withDirectory = new YahooFinanceMarketDataProvider(
                new YahooSymbolResolver(builder.build(), new NseSymbolDirectory(), llmSymbolResolver));
        // The only request: confirming the directory's answer. No guessed BHARTIAIRTEL, no search, no model.
        strict.expect(requestTo(containsString("/v8/finance/chart/BHARTIARTL.NS")))
                .andRespond(withSuccess(chartResponse("1890.10", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = withDirectory.getQuote("Bharti Airtel");

        assertThat(quote.symbol()).isEqualTo("BHARTIARTL");
        strict.verify();
        org.mockito.Mockito.verifyNoInteractions(llmSymbolResolver);
    }

    @Test
    void resolvesExactTickerDirectlyWithoutSearching() {
        server.expect(requestTo(containsString("/v8/finance/chart/WIPRO.NS")))
                .andRespond(withSuccess(chartResponse("245.30", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = provider.getQuote("Wipro");

        assertThat(quote.symbol()).isEqualTo("WIPRO");
        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.price()).isEqualByComparingTo(new BigDecimal("245.30"));
        assertThat(quote.mock()).isFalse();
    }

    @Test
    void resolvesBareTickerThatSearchAloneWouldMissDirectly() {
        // Regression test: Yahoo's fuzzy search does not reliably rank a bare
        // 2-letter ticker like "LT" (Larsen & Toubro) among its top matches,
        // so this must resolve via the direct chart lookup, not search.
        server.expect(requestTo(containsString("/v8/finance/chart/LT.NS")))
                .andRespond(withSuccess(chartResponse("3904.80", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = provider.getQuote("LT");

        assertThat(quote.symbol()).isEqualTo("LT");
        assertThat(quote.price()).isEqualByComparingTo(new BigDecimal("3904.80"));
    }

    @Test
    void fallsBackToNoiseStrippedSearchWhenDirectGuessFails() {
        // Regression test: "Larsen and Toubro" fails a direct guess (no such
        // symbol), and Yahoo's search ranks foreign listings above LT.NS
        // unless "and" is stripped from the query first.
        server.expect(requestTo(containsString("/v8/finance/chart/LARSENTOUBRO.NS")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/LARSENTOUBRO.BO")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search")))
                .andExpect(requestTo(containsString("q=LARSEN%20TOUBRO")))
                .andRespond(withSuccess("""
                        {
                          "quotes": [
                            { "symbol": "LT.NS", "exchange": "NSI", "quoteType": "EQUITY" },
                            { "symbol": "LT.BO", "exchange": "BSE", "quoteType": "EQUITY" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/LT.NS")))
                .andRespond(withSuccess(chartResponse("3904.80", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = provider.getQuote("Larsen and Toubro");

        assertThat(quote.symbol()).isEqualTo("LT");
        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.price()).isEqualByComparingTo(new BigDecimal("3904.80"));
    }

    @Test
    void fallsBackToBseWhenNoNseListingExists() {
        server.expect(requestTo(containsString("/v8/finance/chart/SOME.NS")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/SOME.BO")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search")))
                .andRespond(withSuccess("""
                        { "quotes": [ { "symbol": "SOMECO.BO", "exchange": "BSE", "quoteType": "EQUITY" } ] }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/SOMECO.BO")))
                .andRespond(withSuccess(chartResponse("88.5", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = provider.getQuote("Some Company");

        assertThat(quote.symbol()).isEqualTo("SOMECO");
        assertThat(quote.exchange()).isEqualTo("BSE");
    }

    @Test
    void resolvesViaLlmGuessWhenDirectAndSearchBothFail() {
        // "Bharti Airtel" -> BHARTIARTL is not a simple concatenation of the
        // company name, so neither the direct guess nor search (mocked here
        // as a miss) find it - only the LLM fallback does.
        server.expect(requestTo(containsString("/v8/finance/chart/BHARTIAIRTEL.NS")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/BHARTIAIRTEL.BO")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search")))
                .andRespond(withSuccess("{ \"quotes\": [] }", MediaType.APPLICATION_JSON));
        when(llmSymbolResolver.resolveTickerGuess("Bharti Airtel")).thenReturn(Optional.of("BHARTIARTL"));
        server.expect(requestTo(containsString("/v8/finance/chart/BHARTIARTL.NS")))
                .andRespond(withSuccess(chartResponse("1830.20", "INR"), MediaType.APPLICATION_JSON));

        StockQuote quote = provider.getQuote("Bharti Airtel");

        assertThat(quote.symbol()).isEqualTo("BHARTIARTL");
        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.price()).isEqualByComparingTo(new BigDecimal("1830.20"));
    }

    @Test
    void throwsWhenNoMatchingEquityFoundAnywhere() {
        server.expect(requestTo(containsString("/v8/finance/chart/NOTAREALCOMPANY.NS")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/NOTAREALCOMPANY.BO")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search")))
                .andRespond(withSuccess("{ \"quotes\": [] }", MediaType.APPLICATION_JSON));
        when(llmSymbolResolver.resolveTickerGuess(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getQuote("NotARealCompany"))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    void throwsWhenOnlyNonEquityMatchesExist() {
        server.expect(requestTo(containsString("/v8/finance/chart/SBIN.NS")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v8/finance/chart/SBIN.BO")))
                .andRespond(withSuccess(NOT_FOUND_CHART, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v1/finance/search")))
                .andRespond(withSuccess("""
                        { "quotes": [ { "symbol": "SBINMID150.NS", "exchange": "NSI", "quoteType": "ETF" } ] }
                        """, MediaType.APPLICATION_JSON));
        when(llmSymbolResolver.resolveTickerGuess(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getQuote("SBIN"))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    private String chartResponse(String price, String currency) {
        return """
                {
                  "chart": {
                    "result": [
                      { "meta": { "currency": "%s", "regularMarketPrice": %s, "regularMarketTime": 1789983902 } }
                    ],
                    "error": null
                  }
                }
                """.formatted(currency, price);
    }
}
