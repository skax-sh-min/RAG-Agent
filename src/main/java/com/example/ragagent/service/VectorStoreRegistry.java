package com.example.ragagent.service;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one ChromaVectorStore per document version.
 * Collection naming: manual_{version} with dots replaced by underscores.
 */
@Service
public class VectorStoreRegistry {

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
        return "manual_" + version.replace(".", "_");
    }

    private ChromaVectorStore createStore(String version) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName(version))
                .initializeSchema(true)
                .build();
    }
}
