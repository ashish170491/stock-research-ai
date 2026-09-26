package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooQuoteSummaryClientTest {

    private static final String PROFILE_PAYLOAD = """
            { "quoteSummary": { "result": [ { "assetProfile": { "sector": "Technology" } } ], "error": null } }
            """;

    private MockRestServiceServer server;
    private YahooCrumbProvider crumbProvider;
    private YahooQuoteSummaryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        crumbProvider = mock(YahooCrumbProvider.class);
        when(crumbProvider.get()).thenReturn(Optional.of(
                new YahooCrumbProvider.Credentials("A3=cookie", "crumb123", Instant.MAX)));
        client = new YahooQuoteSummaryClient(builder.build(), crumbProvider);
    }

    @Test
    void sendsTheSessionCookieAndCrumbWithEveryRequest() {
        server.expect(requestTo(containsString("crumb=crumb123")))
                .andExpect(header(HttpHeaders.COOKIE, "A3=cookie"))
                .andRespond(withSuccess(PROFILE_PAYLOAD, MediaType.APPLICATION_JSON));

        assertThat(client.fetch("TCS.NS", "assetProfile").assetProfile().sector()).isEqualTo("Technology");
        server.verify();
    }

    @Test
    void renewsCredentialsAndRetriesOnceWhenYahooRejectsTheCrumb() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("Invalid Crumb"));
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess(PROFILE_PAYLOAD, MediaType.APPLICATION_JSON));

        assertThat(client.fetch("TCS.NS", "assetProfile").assetProfile().sector()).isEqualTo("Technology");
        verify(crumbProvider).invalidate();
        server.verify();
    }

    @Test
    void givesUpAfterASecondRejectionRatherThanRetryingForever() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("Invalid Crumb"));
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("Invalid Crumb"));

        assertThatThrownBy(() -> client.fetch("TCS.NS", "assetProfile"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE);
        server.verify();
    }

    @Test
    void doesNotRetryOnANonAuthErrorBecauseNewCredentialsWouldNotHelp() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.fetch("TCS.NS", "assetProfile"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE);
        verify(crumbProvider, never()).invalidate();
    }

    @Test
    void reportsAnEmptyResultListAsMissingDataRatherThanNullPointering() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": [], "error": null } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch("TCS.NS", "assetProfile"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void reportsAMalformedPayloadAsAProviderErrorNotAsMissingData() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/TCS.NS")))
                .andRespond(withSuccess("{ }", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch("TCS.NS", "assetProfile"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_ERROR);
    }
}
