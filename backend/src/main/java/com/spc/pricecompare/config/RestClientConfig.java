package com.spc.pricecompare.config;

import com.spc.pricecompare.provider.ProviderProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class RestClientConfig {

    /**
     * Every outbound provider call carries an explicit connect and read timeout.
     * Without one, a single unresponsive marketplace would hold a request thread
     * until the platform's own default expired and stall the whole search.
     */
    @Bean
    public RestClient providerRestClient(ProviderProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // The socket deadline, not the search deadline. A search gives up on a
        // slow provider after providers.request-timeout-ms and returns what the
        // others produced; the underlying call is allowed longer so that
        // catalogue seeding, which is not interactive, can complete.
        Duration timeout = Duration.ofMillis(properties.getHttpTimeoutMs());
        factory.setConnectTimeout(Duration.ofMillis(Math.min(10000, properties.getHttpTimeoutMs())));
        factory.setReadTimeout(timeout);

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "SmartPriceComparison/1.0")
                .build();
    }
}
