package com.sheepfold.personalaiworkspaceagent.rag;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChromaDebugService {

    private final ChromaApi chromaApi;
    private final String tenantName;
    private final String databaseName;
    private final String collectionName;

    public ChromaDebugService(
            ChromaApi chromaApi,
            @Value("${spring.ai.vectorstore.chroma.tenant-name:default_tenant}") String tenantName,
            @Value("${spring.ai.vectorstore.chroma.database-name:default_database}") String databaseName,
            @Value("${spring.ai.vectorstore.chroma.collection-name:personal_ai_workspace}") String collectionName) {
        this.chromaApi = chromaApi;
        this.tenantName = tenantName;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    public Map<String, Object> listCollections() {
        List<ChromaApi.Collection> collections = chromaApi.listCollections(tenantName, databaseName);
        List<Map<String, Object>> items = collections.stream()
                .map(collection -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", collection.id() == null ? "" : collection.id());
                    row.put("name", collection.name() == null ? "" : collection.name());
                    return row;
                })
                .toList();

        return Map.of(
                "tenant", tenantName,
                "database", databaseName,
                "collections", items,
                "count", items.size());
    }

    public Map<String, Object> listVectors(Integer limit, Integer offset, boolean withEmbedding) {
        int resolvedLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 200);
        int resolvedOffset = (offset == null || offset < 0) ? 0 : offset;

        ChromaApi.Collection collection = chromaApi.getCollection(tenantName, databaseName, collectionName);
        if (collection == null) {
            return Map.of(
                    "tenant", tenantName,
                    "database", databaseName,
                    "collection", collectionName,
                    "total", 0,
                    "limit", resolvedLimit,
                    "offset", resolvedOffset,
                    "items", List.of());
        }

        List<ChromaApi.QueryRequest.Include> include = new ArrayList<>(
                EnumSet.of(ChromaApi.QueryRequest.Include.DOCUMENTS, ChromaApi.QueryRequest.Include.METADATAS));
        if (withEmbedding) {
            include.add(ChromaApi.QueryRequest.Include.EMBEDDINGS);
        }

        ChromaApi.GetEmbeddingsRequest request = new ChromaApi.GetEmbeddingsRequest(null, null, resolvedLimit,
                resolvedOffset, include);

        ChromaApi.GetEmbeddingResponse response = chromaApi.getEmbeddings(tenantName, databaseName, collection.id(),
                request);

        List<String> ids = response.ids() == null ? List.of() : response.ids();
        List<String> documents = response.documents() == null ? List.of() : response.documents();
        List<Map<String, String>> metadatas = response.metadata() == null ? List.of() : response.metadata();
        List<float[]> embeddings = response.embeddings() == null ? List.of() : response.embeddings();

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String document = i < documents.size() ? documents.get(i) : null;
            Map<String, String> metadata = i < metadatas.size() ? metadatas.get(i) : Map.of();
            if (metadata == null) {
                metadata = Map.of();
            }

            float[] embedding = i < embeddings.size() ? embeddings.get(i) : null;
            Integer embeddingDimension = embedding == null ? null : embedding.length;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id == null ? "" : id);
            item.put("document", document == null ? "" : document);
            item.put("metadata", metadata);
            item.put("embeddingDimension", embeddingDimension == null ? 0 : embeddingDimension);
            if (withEmbedding) {
                item.put("embeddingPreview", embedding == null ? List.of() : toPreview(embedding, 8));
            }

            items.add(item);
        }

        Long total = chromaApi.countEmbeddings(tenantName, databaseName, collection.id());

        return Map.of(
                "tenant", tenantName,
                "database", databaseName,
                "collection", collection.name() == null ? "" : collection.name(),
                "collectionId", collection.id() == null ? "" : collection.id(),
                "total", total == null ? 0 : total,
                "limit", resolvedLimit,
                "offset", resolvedOffset,
                "returned", items.size(),
                "items", items);
    }

    private List<Float> toPreview(float[] embedding, int maxSize) {
        int size = Math.min(embedding.length, maxSize);
        List<Float> preview = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            preview.add(embedding[i]);
        }
        return preview;
    }
}
