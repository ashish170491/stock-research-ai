package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportedValuation;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Tech Mahindra's recorded Yahoo response, 2026-09-25 close: price 1,548.00, P/E 26.74, P/B 4.63, yield 3.29%. */
class YahooFinanceValuationProviderTest {

    private MockRestServiceServer server;
    private YahooFinanceValuationProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution("TECHM.NS", "TECHM", "NSE", null)));
        YahooCrumbProvider crumbs = mock(YahooCrumbProvider.class);
        when(crumbs.get()).thenReturn(Optional.of(new YahooCrumbProvider.Credentials("A3=c", "crumb", Instant.MAX)));
        provider = new YahooFinanceValuationProvider(resolver, new YahooQuoteSummaryClient(builder.build(), crumbs));
    }

    private static String fixture() throws IOException {
        return new ClassPathResource("fixtures/yahoo/techm-quotesummary.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void requestsTheSummaryDetailAndKeyStatisticsModules() throws IOException {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TECHM.NS")))
                .andExpect(queryParam("modules", "summaryDetail,defaultKeyStatistics,price"))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        provider.getReportedValuation("TECHM");

        server.verify();
    }

    @Test
    void readsEachFigureWithTheMeaningYahoosFormattedValueConfirms() throws IOException {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TECHM.NS")))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));

        ReportedValuation v = provider.getReportedValuation("TECHM");

        // regularMarketTime 1790329504 is 15:15 IST on 2026-09-25.
        assertThat(v.asOf()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(v.trailingPe().providerUnit()).isEqualTo(ProviderUnit.MULTIPLE);
        assertThat(v.trailingPe().rawValue()).isEqualByComparingTo("26.73575");
        assertThat(v.trailingPe().display()).isEqualTo("26.74x");
        assertThat(v.trailingPe().source().sourceField()).isEqualTo("summaryDetail.trailingPE");
        assertThat(v.priceToBook().display()).isEqualTo("4.63x");
        // raw 0.032899998, fmt "3.29%": a fraction.
        assertThat(v.dividendYieldPercent().providerUnit()).isEqualTo(ProviderUnit.FRACTION);
        assertThat(v.dividendYieldPercent().display()).isEqualTo("3.29%");
        assertThat(v.price().unit()).isEqualTo(Unit.INR_PER_SHARE);
        assertThat(v.price().display()).isEqualTo("₹1,548.00 per share");
        assertThat(v.trailingEps().display()).isEqualTo("₹57.90 per share");
        assertThat(v.bookValuePerShare().display()).isEqualTo("₹334.41 per share");
        assertThat(v.dividendPerShare().display()).isEqualTo("₹51.00 per share");
        assertThat(v.points()).allSatisfy(p -> assertThat(p.period().label()).isEqualTo("As of 2026-09-25"));
        assertThat(v.provenance().note()).contains("Point-in-time").contains("no fair value, price target or recommendation");
    }

    @Test
    void leavesAMultipleWhoseFormattedValueDoesNotMatchUnverified() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TECHM.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": [{
                            "price": { "longName": "Tech Mahindra Limited", "currency": "INR",
                                       "regularMarketPrice": { "raw": 1548.0, "fmt": "1,548.00" }, "regularMarketTime": 1790329504 },
                            "summaryDetail": { "trailingPE": { "raw": 26.73575, "fmt": "2.67%" } }
                          }], "error": null } }
                        """, MediaType.APPLICATION_JSON));

        ReportedValuation v = provider.getReportedValuation("TECHM");

        assertThat(v.trailingPe().status()).isNotEqualTo(DataStatus.VALID);
        assertThat(v.trailingPe().statusReason()).contains("'2.67%'").contains("a multiple");
        assertThat(v.priceToBook().status()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test
    void refusesAPriceThatIsNotInRupeesRatherThanMixingCurrencies() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TECHM.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": [{
                            "price": { "currency": "USD", "regularMarketPrice": { "raw": 18.5, "fmt": "18.50" } }
                          }], "error": null } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.getReportedValuation("TECHM"))
                .isInstanceOfSatisfying(ResearchDataUnavailableException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
                    assertThat(ex.getMessage()).contains("quoted in USD");
                });
    }
}
