package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.marketdata.http.RequestThrottle;
import com.ashish.stockresearch.research.FiledFinancialsProvider;
import com.ashish.stockresearch.research.FiledStatementChecks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the NSE filings provider. Its HTTP clients are private to it, not beans, so nothing else can
 * pick up an NSE client by type; one throttle covers both, because the limit is on what this application
 * sends NSE in total.
 */
@Configuration
@Profile("!mock")
class NseFilingsConfiguration {

    @Bean
    FiledFinancialsProvider nseFiledFinancialsProvider(RestClient.Builder builder, NseFilingsProperties properties,
                                                       FiledStatementChecks checks) {
        RequestThrottle throttle = new RequestThrottle("NSE", properties.requestsPerSecond());
        RestClient api = client(builder, properties, throttle).baseUrl(properties.apiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.REFERER, properties.referer())
                .build();
        RestClient archive = client(builder, properties, throttle).build();
        return new NseFiledFinancialsProvider(new NseFilingsClient(api, archive, properties.archiveHost()),
                new NseDocumentStore(properties.documentDirectory()), new NseXbrlReader(), checks);
    }

    private static RestClient.Builder client(RestClient.Builder builder, NseFilingsProperties properties,
                                             RequestThrottle throttle) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.timeoutMs());
        requestFactory.setReadTimeout((int) properties.timeoutMs());
        return builder.clone()
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .requestFactory(requestFactory)
                .requestInterceptor(throttle);
    }
}
