package com.sheepfold.personalaiworkspaceagent.rag;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    public Map<String, Object> deleteVectorsBySourceFileName(String sourceFileName) {
        if (!StringUtils.hasText(sourceFileName)) {
            throw new IllegalArgumentException("sourceFileName must not be blank");
        }

        ChromaApi.Collection collection = chromaApi.getCollection(tenantName, databaseName, collectionName);
        if (collection == null) {
            return Map.of(
                    "tenant", tenantName,
                    "database", databaseName,
                    "collection", collectionName,
                    "sourceFileName", sourceFileName,
                    "deleted", 0,
                    "totalBefore", 0,
                    "totalAfter", 0,
                    "deleteStatus", 404);
        }

        Map<String, Object> where = Map.of(LlamaParseDocumentLoader.SOURCE_FILE_NAME_METADATA_KEY, sourceFileName);

        Long totalBefore = chromaApi.countEmbeddings(tenantName, databaseName, collection.id());
        long before = totalBefore == null ? 0 : totalBefore;

        ChromaApi.GetEmbeddingsRequest previewRequest = new ChromaApi.GetEmbeddingsRequest(
                null,
                where,
                50,
                0,
                List.of(ChromaApi.QueryRequest.Include.METADATAS));
        ChromaApi.GetEmbeddingResponse previewResponse = chromaApi.getEmbeddings(
                tenantName,
                databaseName,
                collection.id(),
                previewRequest);

        List<String> previewIds = previewResponse.ids() == null ? List.of() : previewResponse.ids();

        int statusCode = chromaApi.deleteEmbeddings(
                tenantName,
                databaseName,
                collection.id(),
                new ChromaApi.DeleteEmbeddingsRequest(null, where));

        Long totalAfter = chromaApi.countEmbeddings(tenantName, databaseName, collection.id());
        long after = totalAfter == null ? 0 : totalAfter;
        long deleted = Math.max(0, before - after);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant", tenantName);
        result.put("database", databaseName);
        result.put("collection", collection.name() == null ? "" : collection.name());
        result.put("collectionId", collection.id() == null ? "" : collection.id());
        result.put("sourceFileName", sourceFileName);
        result.put("matchedPreviewCount", previewIds.size());
        result.put("matchedPreviewIds", previewIds.size() <= 20 ? previewIds : previewIds.subList(0, 20));
        result.put("deleteStatus", statusCode);
        result.put("totalBefore", before);
        result.put("totalAfter", after);
        result.put("deleted", deleted);
        return result;
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
