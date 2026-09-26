package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceFinancialDataProviderTest {

    private MockRestServiceServer server;
    private YahooSymbolResolver resolver;
    private YahooCrumbProvider crumbProvider;
    private YahooFinanceFinancialDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();

        resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution("TCS.NS", "TCS", "NSE", null)));

        crumbProvider = mock(YahooCrumbProvider.class);
        when(crumbProvider.get()).thenReturn(Optional.of(
                new YahooCrumbProvider.Credentials("A3=cookie", "crumb123", java.time.Instant.MAX)));

        provider = new YahooFinanceFinancialDataProvider(
                resolver, new YahooQuoteSummaryClient(builder.build(), crumbProvider));
    }

    private String quoteSummary(String financialData, String keyStatistics) {
        return """
                {
                  "quoteSummary": {
                    "result": [{
                      "price": { "longName": "Tata Consultancy Services Limited" },
                      "financialData": %s,
                      "defaultKeyStatistics": %s
                    }],
                    "error": null
                  }
                }
                """.formatted(financialData, keyStatistics);
    }

    private static final String FULL_FINANCIALS = """
            {
              "totalRevenue": { "raw": 2758590070784 },
              "ebitda": { "raw": 720690020352 },
              "totalCash": { "raw": 450310012928 },
              "totalDebt": { "raw": 113090002944 },
              "debtToEquity": { "raw": 10.211 },
              "freeCashflow": { "raw": 397165002752 },
              "operatingMargins": { "raw": 0.23963 },
              "profitMargins": { "raw": 0.18052 },
              "returnOnEquity": { "raw": 0.47743 },
              "returnOnAssets": { "raw": 0.24479 },
              "revenueGrowth": { "raw": 0.139 },
              "earningsGrowth": { "raw": 0.046 },
              "financialCurrency": "INR"
            }
            """;

    private static final String FULL_STATISTICS = """
            {
              "netIncomeToCommon": { "raw": 497990008832 },
              "mostRecentQuarter": { "raw": 1782777600, "fmt": "2026-06-30" },
              "lastFiscalYearEnd": { "raw": 1774915200, "fmt": "2026-03-31" }
            }
            """;

    @Test
    void convertsYahooFractionsIntoPercentagesTheModelCanRead() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary(FULL_FINANCIALS, FULL_STATISTICS),
                        MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.operatingMarginPercent()).isEqualByComparingTo("23.96");
        assertThat(summary.netProfitMarginPercent()).isEqualByComparingTo("18.05");
        assertThat(summary.returnOnEquityPercent()).isEqualByComparingTo("47.74");
        assertThat(summary.revenueGrowthPercent()).isEqualByComparingTo("13.90");
    }

    @Test
    void passesThroughDebtToEquityWhichYahooAlreadyReportsAsAPercentage() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary(FULL_FINANCIALS, FULL_STATISTICS),
                        MediaType.APPLICATION_JSON));

        // 10.211 means 10.21%, not 1021% - this one must NOT be multiplied by 100.
        FinancialSummary summary = provider.getFinancialSummary("TCS");
        assertThat(summary.debtToEquityPercent()).isEqualByComparingTo("10.21");
        // Spelled out in the payload because a model reading "10.21" alone will
        // render it as "10.21x" and call a debt-free company heavily geared.
        assertThat(summary.provenance().note()).contains("0.10x");
    }

    @Test
    void readsAbsoluteAmountsAndTheReportingPeriodStraightFromTheProvider() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary(FULL_FINANCIALS, FULL_STATISTICS),
                        MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.revenue()).isEqualByComparingTo("2758590070784");
        assertThat(summary.netProfit()).isEqualByComparingTo("497990008832");
        assertThat(summary.mostRecentQuarterEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(summary.lastFiscalYearEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(summary.reportingPeriod()).contains("2026-06-30");
    }

    @Test
    void marksFilingDataAsPeriodicSoItIsNotPresentedAsCurrent() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary(FULL_FINANCIALS, FULL_STATISTICS),
                        MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.provenance().freshness()).isEqualTo(DataFreshness.PERIODIC_FILING);
        assertThat(summary.provenance().asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(summary.provenance().note()).contains("NOT live figures");
    }

    @Test
    void alwaysReportsRoceAsUnavailableRatherThanDerivingIt() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary(FULL_FINANCIALS, FULL_STATISTICS),
                        MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.returnOnCapitalEmployedPercent()).isNull();
        assertThat(summary.unavailableMetrics()).contains("returnOnCapitalEmployedPercent");
        assertThat(summary.provenance().note()).contains("ROCE is never available");
    }

    @Test
    void namesEveryMissingMetricInsteadOfLettingNullLookLikeZero() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary("""
                        { "totalRevenue": { "raw": 100 }, "financialCurrency": "INR" }
                        """, "{}"), MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.ebitda()).isNull();
        assertThat(summary.totalDebt()).isNull();
        // Bare metric names, not sentences - the "unknown, not zero" reason is
        // stated once in the provenance note instead of on every entry.
        assertThat(summary.unavailableMetrics())
                .contains("ebitda", "totalDebt", "returnOnCapitalEmployedPercent")
                .allSatisfy(entry -> assertThat(entry).doesNotContain(" "));
        assertThat(summary.provenance().note()).contains("unknown, not zero");
    }

    @Test
    void flagsLoudlyWhenTheAccountsAreNotReportedInRupees() {
        // Yahoo sources some Indian companies' financials from their US listing
        // and reports them in USD even though the shares trade in INR.
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary("""
                        { "totalRevenue": { "raw": 20298999808 }, "financialCurrency": "USD" }
                        """, FULL_STATISTICS), MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("INFY");

        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.provenance().note())
                .contains("reported in USD, not INR")
                .contains("Do not present these as rupee figures");
    }

    @Test
    void saysSoWhenEvenTheReportingCurrencyIsUnknown() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary("""
                        { "totalRevenue": { "raw": 100 } }
                        """, FULL_STATISTICS), MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.currency()).isNull();
        assertThat(summary.provenance().note()).contains("reporting currency is unknown");
    }

    @Test
    void toleratesBareNumbersWhereYahooNormallyWrapsThemInARawObject() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(quoteSummary("""
                        { "totalRevenue": 12345, "operatingMargins": 0.25, "financialCurrency": "INR" }
                        """, FULL_STATISTICS), MediaType.APPLICATION_JSON));

        FinancialSummary summary = provider.getFinancialSummary("TCS");

        assertThat(summary.revenue()).isEqualByComparingTo("12345");
        assertThat(summary.operatingMarginPercent()).isEqualByComparingTo("25.00");
    }

    @Test
    void reportsAYahooErrorPayloadAsMissingDataNotAsSuccess() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": null,
                          "error": { "code": "Not Found", "description": "No fundamentals" } } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.getFinancialSummary("TCS"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void reportsAnAuthenticationFailureAsAProviderProblemNotAsMissingFinancials() {
        when(crumbProvider.get()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getFinancialSummary("TCS"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE);
    }

    @Test
    void reportsAnUnresolvableSymbolAsNotFound() {
        when(resolver.resolve("NOTACOMPANY")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getFinancialSummary("NOTACOMPANY"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.SYMBOL_NOT_FOUND);
    }
}
