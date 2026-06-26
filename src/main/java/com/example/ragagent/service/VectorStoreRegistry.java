package com.example.ragagent.service;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one ChromaVectorStore per (userId, version) pair.
 *
 * Collection naming: u_{userId8}_{version}
 *   - userId8: first 8 chars of userId with hyphens removed, non-alphanumeric → '_'
 *   - Chroma constrains collection names to [a-zA-Z0-9._-]{3,63}, start/end alphanumeric.
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "chroma", matchIfMissing = true)
public class VectorStoreRegistry {

    private static final int MAX_NAME_LEN = 63;
    private static final String FALLBACK_VERSION = "latest";

    private final ConcurrentHashMap<String, ChromaVectorStore> stores = new ConcurrentHashMap<>();
    private final ChromaApi chromaApi;
    private final EmbeddingModel embeddingModel;

    public VectorStoreRegistry(ChromaApi chromaApi, EmbeddingModel embeddingModel) {
        this.chromaApi = chromaApi;
        this.embeddingModel = embeddingModel;
    }

    public VectorStore getStore(String userId, String version) {
        String key = userId + ":" + version;
        return stores.computeIfAbsent(key, k -> createStore(userId, version));
    }

    public void evict(String userId, String version) {
        stores.remove(userId + ":" + version);
    }

    public String collectionName(String userId, String version) {
        // userId → take first 8 alphanumeric chars (hyphens stripped)
        String uid = userId == null || userId.isBlank() ? "anonymous" : userId;
        uid = uid.replace("-", "").replaceAll("[^a-zA-Z0-9]", "_");
        uid = uid.substring(0, Math.min(8, uid.length()));
        if (uid.isBlank()) uid = "anon";

        String ver = (version == null || version.isBlank())
                ? FALLBACK_VERSION
                : version.replaceAll("[^a-zA-Z0-9._\\-]", "_");

        String name = "u_" + uid + "_" + ver;
        if (name.length() > MAX_NAME_LEN) name = name.substring(0, MAX_NAME_LEN);

        // Chroma forbids trailing non-alphanumerics
        int end = name.length();
        while (end > 3 && !Character.isLetterOrDigit(name.charAt(end - 1))) end--;
        return end < 3 ? "u_anon_latest" : name.substring(0, end);
    }

    private ChromaVectorStore createStore(String userId, String version) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName(userId, version))
                .initializeSchema(true)
                .initializeImmediately(true)
                .build();
    }
}
