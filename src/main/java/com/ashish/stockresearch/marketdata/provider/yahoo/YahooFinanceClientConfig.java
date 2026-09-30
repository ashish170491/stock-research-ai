package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.marketdata.http.RequestThrottle;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("!mock")
public class YahooFinanceClientConfig {

    /** One throttle for both clients: the limit is on what this application sends Yahoo in total. */
    @Bean
    RequestThrottle yahooRequestThrottle(YahooFinanceProperties properties) {
        return new RequestThrottle("Yahoo Finance", properties.requestsPerSecond());
    }

    @Bean
    public RestClient yahooFinanceRestClient(RestClient.Builder builder, YahooFinanceProperties properties,
                                             @Qualifier("yahooRequestThrottle") RequestThrottle throttle) {
        return yahooClient(builder, properties, properties.baseUrl(), throttle);
    }

    /**
     * Separate client for the cookie handshake, because the host that issues
     * the session cookie is not the host that serves the API.
     */
    @Bean
    public RestClient yahooCookieRestClient(RestClient.Builder builder, YahooFinanceProperties properties,
                                            @Qualifier("yahooRequestThrottle") RequestThrottle throttle) {
        return yahooClient(builder, properties, properties.cookieUrl(), throttle);
    }

    private RestClient yahooClient(RestClient.Builder builder, YahooFinanceProperties properties, String baseUrl,
                                   RequestThrottle throttle) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) properties.timeoutMs();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .requestFactory(requestFactory)
                .requestInterceptor(throttle)
                .build();
    }
}
