package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.MarketDataProvider;
import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.TestFilings;
import com.ashish.stockresearch.research.ValuationService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wires the real (non-mock) Yahoo-backed research providers against {@link MockRestServiceServer} fixtures
 * for HDFC Bank and Tech Mahindra - the golden set's companies (roadmap Step 10) - so the llm suite exercises
 * genuine provider and calculation code with no live Yahoo call. Same pattern as
 * {@link ResearchPipelineRegressionTest}: {@link YahooSymbolResolver} is mocked (one instance here, answering
 * for both symbols instead of one per test), since the confirming chart lookup it would otherwise make needs
 * a 1-day-range quote (a live price), which these companies' recorded 5-year-range chart fixtures do not
 * carry - confirmed by reproducing the resolver's own null-price check against them directly. The quote path
 * ({@code getStockQuote}/{@code getQuote}) is left to report "no quote is available" for the same reason: no
 * fixture gives it a genuine live price to show, and inventing one would show the golden set a number no
 * capture ever produced.
 */
public final class GoldenSetYahooFixtures {

    public static final List<String> SYMBOLS = List.of("HDFCBANK", "TECHM");

    private GoldenSetYahooFixtures() {
    }

    public record Wiring(StockResearchService research, MarketDataProvider marketData) {
    }

    public static Wiring wire() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        for (String symbol : SYMBOLS) {
            String name = symbol.toLowerCase(Locale.ROOT);
            server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/v10/finance/quoteSummary/" + symbol + ".NS")))
                    .andRespond(withSuccess(fixture(name + "-quotesummary.json"), MediaType.APPLICATION_JSON));
            server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/timeseries/" + symbol + ".NS")))
                    .andRespond(withSuccess(fixture(name + "-timeseries.json"), MediaType.APPLICATION_JSON));
            server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/v8/finance/chart/" + symbol + ".NS")))
                    .andRespond(withSuccess(fixture(name + "-chart-5y.json"), MediaType.APPLICATION_JSON));
        }
        RestClient client = builder.build();

        YahooCrumbProvider crumbs = mock(YahooCrumbProvider.class);
        when(crumbs.get()).thenReturn(Optional.of(new YahooCrumbProvider.Credentials("A3=c", "crumb", Instant.MAX)));
        YahooQuoteSummaryClient quoteSummary = new YahooQuoteSummaryClient(client, crumbs);
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        for (String symbol : SYMBOLS) {
            when(resolver.resolve(argThat(input -> matches(input, symbol))))
                    .thenReturn(Optional.of(new YahooSymbolResolver.Resolution(symbol + ".NS", symbol, "NSE", null)));
        }
        when(resolver.resolveWithLatestQuote(any())).thenReturn(Optional.empty());
        FinancialDataValidator validator = new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026);

        StockResearchService research = new StockResearchService(
                new YahooFinanceCompanyProfileProvider(resolver, quoteSummary, validator),
                new YahooFinanceFinancialDataProvider(resolver, quoteSummary, validator),
                new UnsupportedShareholdingProvider(),
                new YahooFinanceHistoricalMarketDataProvider(client, resolver),
                new FinancialMetricsService(), new HistoricalPerformanceService(),
                new YahooFinanceValuationProvider(resolver, quoteSummary),
                new ValuationService(BigDecimal.valueOf(5)),
                TestFilings.none());

        MarketDataProvider marketData = new YahooFinanceMarketDataProvider(resolver);
        return new Wiring(research, marketData);
    }

    /** Matches the exact symbol, or that symbol's company name with any casing/spacing, from the directory. */
    private static boolean matches(String input, String symbol) {
        if (input == null) {
            return false;
        }
        String normalised = input.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalised.equals(symbol) || normalised.startsWith(symbol);
    }

    private static String fixture(String file) {
        try {
            return new ClassPathResource("fixtures/yahoo/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
