package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
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
 * Exercises the return arithmetic against a hand-built series whose answers
 * can be checked by hand. No real Yahoo call is made.
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
    void derivesTotalReturnAndCagrFromTheAdjustedSeries() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.startPrice()).isEqualByComparingTo("100.0");
        assertThat(performance.endPrice()).isEqualByComparingTo("400.0");
        // 4x over three years -> 300% total, and 4^(1/3)-1 = 58.74% a year.
        assertThat(performance.totalReturnPercent()).isEqualByComparingTo("300.00");
        assertThat(performance.cagrPercent()).isEqualByComparingTo("58.74");
        assertThat(performance.actualYears()).isEqualByComparingTo("3.00");
    }

    @Test
    void measuresDrawdownFromTheRunningPeakNotFromTheStart() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        // 200 -> 150 is the worst peak-to-trough fall, even though the series ends up.
        assertThat(performance.maxDrawdownPercent()).isEqualByComparingTo("-25.00");
        assertThat(performance.periodHigh()).isEqualByComparingTo("400.0");
        assertThat(performance.periodLow()).isEqualByComparingTo("100.0");
    }

    @Test
    void leavesTheFirstCalendarYearReturnNullRatherThanCallingItZero() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.calendarYearReturns()).hasSize(4);
        CalendarYearReturn first = performance.calendarYearReturns().get(0);
        assertThat(first.year()).isEqualTo(2021);
        assertThat(first.closingPrice()).isEqualByComparingTo("100.00");
        assertThat(first.returnPercent()).isNull();
        assertThat(performance.calendarYearReturns().get(1).returnPercent()).isEqualByComparingTo("100.00");
        // The null must be explained in the payload, not only in the javadoc -
        // the model sees the JSON and will otherwise fill the gap itself.
        assertThat(performance.provenance().note()).contains("null returnPercent");
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

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.periodStart()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(performance.calendarYearReturns().get(0).year()).isEqualTo(2022);
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

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        // A zero-filled gap would have produced a -100% drawdown and a nonsense CAGR.
        assertThat(performance.calendarYearReturns()).hasSize(2);
        assertThat(performance.periodLow()).isEqualByComparingTo("100.0");
        assertThat(performance.totalReturnPercent()).isEqualByComparingTo("50.00");
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

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.totalReturnPercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void labelsTheDataHistoricalSoItIsNotReadAsALiveQuote() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.provenance().freshness()).isEqualTo(DataFreshness.HISTORICAL);
        assertThat(performance.provenance().asOf()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(performance.provenance().note()).contains("NOT a live quote");
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

        assertThatThrownBy(() -> provider.getHistoricalPerformance("TCS", 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void distinguishesAProviderOutageFromMissingData() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.getHistoricalPerformance("TCS", 5))
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
                .getHistoricalPerformance("NOTACOMPANY", 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.SYMBOL_NOT_FOUND);
    }

    @Test
    void widensTheRequestedWindowToARangeYahooAccepts() {
        server.expect(requestTo(containsString("range=5y")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        // Yahoo has no "3y" token, so a 3-year ask must be widened to 5y.
        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 3);

        assertThat(performance.requestedYears()).isEqualTo(3);
        server.verify();
    }

    @Test
    void assertBigDecimalScaleIsStable() {
        server.expect(requestTo(containsString("/v8/finance/chart/TCS.NS")))
                .andRespond(withSuccess(FOUR_YEAR_SERIES, MediaType.APPLICATION_JSON));

        HistoricalPerformance performance = provider.getHistoricalPerformance("TCS", 5);

        assertThat(performance.totalReturnPercent().scale()).isEqualTo(2);
        assertThat(performance.cagrPercent()).isNotNull();
        assertThat(performance.currency()).isEqualTo("INR");
        assertThat(BigDecimal.ZERO.compareTo(performance.actualYears())).isNegative();
    }
}
