package com.example.ragagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ChromaConfig {

    @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost}")
    private String chromaHost;

    @Value("${spring.ai.vectorstore.chroma.client.port:8001}")
    private int chromaPort;

    @Bean
    ChromaApi chromaApi(ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                               ObjectMapper objectMapper) {
        String baseUrl = String.format("%s:%s", chromaHost, chromaPort);
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return ChromaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(builder)
                .objectMapper(objectMapper)
                .build();
    }
}
