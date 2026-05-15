package com.example.ragagent.service;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one ChromaVectorStore per document version.
 *
 * Chroma constrains collection names to {@code [a-zA-Z0-9._-]{3,63}}, must
 * start with an alphanumeric, and must not end with a non-alphanumeric. This
 * registry sanitizes arbitrary version strings (including Korean, slashes,
 * over-length, etc.) into a valid collection name.
 */
@Service
public class VectorStoreRegistry {

    private static final String PREFIX = "manual_";
    private static final int MAX_NAME_LEN = 63;
    private static final String FALLBACK_VERSION = "latest";

    private final ConcurrentHashMap<String, ChromaVectorStore> stores = new ConcurrentHashMap<>();
    private final ChromaApi chromaApi;
    private final EmbeddingModel embeddingModel;

    public VectorStoreRegistry(ChromaApi chromaApi, EmbeddingModel embeddingModel) {
        this.chromaApi = chromaApi;
        this.embeddingModel = embeddingModel;
    }

    public VectorStore getStore(String version) {
        return stores.computeIfAbsent(version, this::createStore);
    }

    public void evict(String version) {
        stores.remove(version);
    }

    public String collectionName(String version) {
        String sanitized = (version == null || version.isBlank())
                ? FALLBACK_VERSION
                : version.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String name = PREFIX + sanitized;
        if (name.length() > MAX_NAME_LEN) name = name.substring(0, MAX_NAME_LEN);
        // Chroma forbids trailing non-alphanumerics (e.g. trailing '_' or '.').
        int end = name.length();
        while (end > PREFIX.length() && !Character.isLetterOrDigit(name.charAt(end - 1))) end--;
        return end == PREFIX.length() ? PREFIX + FALLBACK_VERSION : name.substring(0, end);
    }

    private ChromaVectorStore createStore(String version) {
        // initializeImmediately(true) is required: without it, build() skips afterPropertiesSet()
        // and collectionId is never set, causing "null" to appear in the ChromaDB upsert URL.
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName(version))
                .initializeSchema(true)
                .initializeImmediately(true)
                .build();
    }
}
