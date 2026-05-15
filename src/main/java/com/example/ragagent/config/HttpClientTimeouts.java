package com.example.ragagent.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Utility for creating RestClient builders with explicit connect/read timeouts.
 */
public final class HttpClientTimeouts {

    private HttpClientTimeouts() {}

    public static RestClient.Builder restClientBuilder(int connectTimeoutSeconds, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutSeconds * 1000);
        requestFactory.setReadTimeout(readTimeoutSeconds * 1000);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
