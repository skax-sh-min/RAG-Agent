package com.example.ragagent.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.common.ChromaApiConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ChromaHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(ChromaHealthChecker.class);

    private final ChromaApi chromaApi;
    private final String chromaHost;
    private final int chromaPort;

    public ChromaHealthChecker(ChromaApi chromaApi,
            @Value("${spring.ai.vectorstore.chroma.client.host:http://localhost}") String chromaHost,
            @Value("${spring.ai.vectorstore.chroma.client.port:8001}") int chromaPort) {
        this.chromaApi = chromaApi;
        this.chromaHost = chromaHost;
        this.chromaPort = chromaPort;
    }

    @PostConstruct
    void checkConnection() {
        try {
            chromaApi.listCollections(
                    ChromaApiConstants.DEFAULT_TENANT_NAME,
                    ChromaApiConstants.DEFAULT_DATABASE_NAME);
            log.info("[CHROMA] Connected to ChromaDB at {}:{}", chromaHost, chromaPort);
        } catch (ResourceAccessException e) {
            log.warn("[CHROMA] ChromaDB NOT reachable at {}:{} — start with: docker-compose up chroma",
                    chromaHost, chromaPort);
        } catch (Exception e) {
            // HTTP error (e.g. 404 database not yet created) still means ChromaDB is running
            log.info("[CHROMA] ChromaDB reachable at {}:{} ({})", chromaHost, chromaPort, e.getMessage());
        }
    }
}
