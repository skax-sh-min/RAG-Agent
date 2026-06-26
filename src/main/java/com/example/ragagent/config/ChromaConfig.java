package com.example.ragagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** {@code app.vectorstore.type=chroma}(기본값)일 때만 {@link ChromaApi} 빈을 생성한다. */
@Configuration
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "chroma", matchIfMissing = true)
public class ChromaConfig {

    @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost}")
    private String chromaHost;

    @Value("${spring.ai.vectorstore.chroma.client.port:8001}")
    private int chromaPort;

    @Bean
    ChromaApi chromaApi(AppProperties props, ObjectMapper objectMapper) {
        String baseUrl = String.format("%s:%s", chromaHost, chromaPort);
        AppProperties.ChromaHttpConfig timeoutCfg = props.chromaSafe();
        RestClient.Builder builder = HttpClientTimeouts.restClientBuilder(
                timeoutCfg.connectTimeoutSeconds(),
                timeoutCfg.readTimeoutSeconds());
        return ChromaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(builder)
                .objectMapper(objectMapper)
                .build();
    }
}
