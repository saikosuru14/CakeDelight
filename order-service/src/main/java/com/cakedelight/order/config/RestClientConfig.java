package com.cakedelight.order.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP configuration for reads against the Catalog Service.
 *
 * <p>The base URL comes from {@code CATALOG_SERVICE_URL}. Connect and read timeouts are
 * five seconds each, so an unreachable or slow catalog surfaces as a failure the caller can
 * map to 503 instead of hanging the request thread.
 *
 * <p>Retry on top of these timeouts is declared on
 * {@link com.cakedelight.order.client.CatalogClient}, which is the only component that knows which
 * failures are transient. This bean stays retry-unaware so a failure always reaches that decision
 * point. No circuit breaker layer.
 */
@Configuration
public class RestClientConfig {

    private final String catalogServiceUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public RestClientConfig(
            @Value("${catalog.service.url}") String catalogServiceUrl,
            @Value("${catalog.service.connect-timeout:5s}") Duration connectTimeout,
            @Value("${catalog.service.read-timeout:5s}") Duration readTimeout) {
        this.catalogServiceUrl = catalogServiceUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Bean
    public RestClient catalogRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(catalogServiceUrl)
                .requestFactory(clientHttpRequestFactory())
                .build();
    }

    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
