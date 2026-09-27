package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceCompanyProfileProviderTest {

    private MockRestServiceServer server;
    private YahooFinanceCompanyProfileProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution("HDFCBANK.NS", "HDFCBANK", "NSE", null)));
        YahooCrumbProvider crumbProvider = mock(YahooCrumbProvider.class);
        when(crumbProvider.get()).thenReturn(Optional.of(
                new YahooCrumbProvider.Credentials("A3=cookie", "crumb123", Instant.MAX)));
        Clock clock = Clock.fixed(Instant.parse("2026-09-26T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        provider = new YahooFinanceCompanyProfileProvider(resolver,
                new YahooQuoteSummaryClient(builder.build(), crumbProvider), new FinancialDataValidator(clock));
    }

    private void respond(String marketCapRaw, String marketCapFmt) {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/HDFCBANK.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": [{
                            "assetProfile": { "sector": "Financial Services", "industry": "Banks - Regional",
                                              "longBusinessSummary": "HDFC Bank Limited provides banking and financial services." },
                            "price": { "longName": "HDFC Bank Limited", "currency": "INR",
                                       "marketCap": { "raw": %s, "fmt": "%s" }, "regularMarketPrice": { "raw": 735.6, "fmt": "735.60" },
                                       "regularMarketTime": 1790401800 },
                            "defaultKeyStatistics": { "sharesOutstanding": { "raw": 15418477012, "fmt": "15.42B" } }
                          }], "error": null } }
                        """.formatted(marketCapRaw, marketCapFmt), MediaType.APPLICATION_JSON));
    }

    @Test
    void normalisesMarketCapToElevenPointThreeFourLakhCroreNotOneHundredThirteen() {
        respond("11341831077888", "11.34T");

        CompanyProfile profile = provider.getCompanyProfile("HDFC Bank");

        assertThat(profile.marketCap().unit()).isEqualTo(Unit.INR_CRORE);
        assertThat(profile.marketCap().value()).isEqualByComparingTo("1134183.11");
        assertThat(profile.marketCap().display()).isEqualTo("₹11.34 lakh crore (₹11,34,183 crore)");
        assertThat(profile.dataQualityIssues()).isEmpty();
        assertThat(profile.marketCap().rawValue()).isEqualByComparingTo("11341831077888");
        assertThat(profile.provenance().source()).isEqualTo("Yahoo Finance");
    }

    @Test
    void datesMarketCapByTheLastTradeOnTheExchangeCalendar() {
        respond("11341831077888", "11.34T");

        CompanyProfile profile = provider.getCompanyProfile("HDFC Bank");

        // 1790401800 = 2026-09-26 05:50 UTC = 11:20 IST
        assertThat(profile.marketCap().period().type()).isEqualTo(PeriodType.POINT_IN_TIME);
        assertThat(profile.marketCap().source().asOf()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(profile.marketCap().source().sourceField()).isEqualTo("price.marketCap");
    }

    @Test
    void flagsAMarketCapThatDisagreesWithPriceTimesSharesByAFactorOfTen() {
        respond("113418310778880", "113.42T");

        CompanyProfile profile = provider.getCompanyProfile("HDFC Bank");

        assertThat(profile.dataQualityIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
            assertThat(issue.message()).contains("wrong unit");
        });
    }

    @Test
    void rejectsAMarketCapWhoseRawValueDisagreesWithYahoosOwnFormat() {
        // A 10x error inside the payload itself: raw says 113 trillion, fmt says 11.34T.
        respond("113418310778880", "11.34T");

        CompanyProfile profile = provider.getCompanyProfile("HDFC Bank");

        assertThat(profile.marketCap().available()).isFalse();
        assertThat(profile.marketCap().unavailableReason()).startsWith("metric semantics could not be verified");
    }
}
