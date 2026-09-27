package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises series parsing: dates, timezone, gaps and fallbacks. The return
 * arithmetic lives in HistoricalPerformanceService and is tested there.
 * No real Yahoo call is made.
 */
class YahooFinanceHistoricalMarketDataProviderTest {

    /**
     * Four 15:30 IST year-end closes (31 Dec 2021 through 31 Dec 2024) with an
     * easy shape: 100 -> 200 -> 150 -> 400. The dip to 150 gives a known -25%
     * drawdown from the 200 peak.
     */
    private static final String FOUR_YEAR_SERIES = """
            {
              "chart": {
                "result": [{
                  "meta": { "currency": "INR" },
                  "timestamp": [1640944800, 1672480800, 1704016800, 1735639200],
                  "indicators": {
                    "adjclose": [{ "adjclose": [100.0, 200.0, 150.0, 400.0] }]
                  }
                }],
                "error": null
              }
            }
            """;

    private MockRestServiceServer server;
    private YahooFinanceHistoricalMarketDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve("TCS")).thenReturn(Optional.of(new YahooSymbolResolver.Resolution(
                "TCS.NS", "TCS", "NSE", null)));
        provider = new YahooFinanceHistoricalMarketDataProvider(builder.build(), resolver);
    }

    @Test
    void returnsTheAdjustedSeriesAsDatedPricesWithoutCalculatingAnything() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.getPriceHistory("TCS", 5);

        assertThat(history.closes()).extracting(PricePoint::close)
                .extracting(BigDecimal::doubleValue).containsExactly(100.0, 200.0, 150.0, 400.0);
        assertThat(history.closes().get(0).date()).isEqualTo(LocalDate.of(2021, 12, 31));
        assertThat(history.currency()).isEqualTo("INR");
    }

    @Test
    void requestsDailyBarsSoDatesMatchTheirClosingPrices() {
        server.expect(requestTo(containsString("interval=1d")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        provider.getPriceHistory("TCS", 5);

        server.verify();
    }

    @Test
    void bucketsClosesByExchangeTimezoneNotUtc() {
        // 1640975400 is 2021-12-31 18:30 UTC, which is already 2022-01-01 in
        // Mumbai. Bucketing by UTC date would file this close under 2021 and
        // shift every calendar-year return by one.
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": { "currency": "INR" },
                              "timestamp": [1640975400, 1672480800],
                              "indicators": { "adjclose": [{ "adjclose": [100.0, 120.0] }] }
                            }],
                            "error": null
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.getPriceHistory("TCS", 5);

        assertThat(history.closes().get(0).date()).isEqualTo(LocalDate.of(2022, 1, 1));
    }

    @Test
    void skipsMissingPricesInsteadOfReadingThemAsZero() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": { "currency": "INR" },
                              "timestamp": [1640944800, 1672480800, 1704016800],
                              "indicators": { "adjclose": [{ "adjclose": [100.0, null, 150.0] }] }
                            }],
                            "error": null
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.getPriceHistory("TCS", 5);

        // A zero-filled gap would have produced a -100% drawdown and a nonsense CAGR.
        assertThat(history.closes()).extracting(PricePoint::close)
                .extracting(BigDecimal::doubleValue).containsExactly(100.0, 150.0);
    }

    @Test
    void fallsBackToRawClosesWhenTheAdjustedSeriesIsAbsent() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": { "currency": "INR" },
                              "timestamp": [1640944800, 1672480800],
                              "indicators": { "quote": [{ "close": [100.0, 110.0] }] }
                            }],
                            "error": null
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.getPriceHistory("TCS", 5);

        assertThat(history.closes().get(1).close()).isEqualByComparingTo("110.0");
    }

    @Test
    void labelsTheDataHistoricalSoItIsNotReadAsALiveQuote() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.getPriceHistory("TCS", 5);

        assertThat(history.provenance().freshness()).isEqualTo(DataFreshness.HISTORICAL);
        assertThat(history.provenance().asOf()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(history.provenance().note()).contains("NOT a live quote").contains("never recalculate");
    }

    @Test
    void reportsTooShortAHistoryAsUnavailableRatherThanGuessing() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess("""
                        {
                          "chart": {
                            "result": [{
                              "meta": { "currency": "INR" },
                              "timestamp": [1672480800],
                              "indicators": { "adjclose": [{ "adjclose": [200.0] }] }
                            }],
                            "error": null
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.getPriceHistory("TCS", 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void distinguishesAProviderOutageFromMissingData() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.getPriceHistory("TCS", 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE);
    }

    @Test
    void reportsAnUnresolvableSymbolAsNotFound() {
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve("NOTACOMPANY")).thenReturn(Optional.empty());
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");

        assertThatThrownBy(() -> new YahooFinanceHistoricalMarketDataProvider(builder.build(), resolver)
                .getPriceHistory("NOTACOMPANY", 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.SYMBOL_NOT_FOUND);
    }

    @Test
    void widensTheRequestedWindowToARangeYahooAccepts() {
        server.expect(requestTo(containsString("range=5y")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        // Yahoo has no "3y" token, so a 3-year ask must be widened to 5y (the service trims it back).
        provider.getPriceHistory("TCS", 3);

        server.verify();
    }
}
