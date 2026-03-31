package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RagService {

    private static final String MANUAL_SOURCE_FILE = "manual-input";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final LlamaParseDocumentLoader llamaParseDocumentLoader;
    private final int embeddingBatchSize;

    public RagService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            LlamaParseDocumentLoader llamaParseDocumentLoader,
            @Value("${rag.embedding.batch-size:10}") int embeddingBatchSize) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.llamaParseDocumentLoader = llamaParseDocumentLoader;
        this.embeddingBatchSize = Math.max(1, Math.min(10, embeddingBatchSize));
    }

    public int ingest(RagIngestRequest request) {
        if (request == null || request.chunks() == null || request.chunks().isEmpty()) {
            return 0;
        }

        List<Document> documents = request.chunks().stream()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .map(chunk -> new Document(chunk, Map.of(
                        LlamaParseDocumentLoader.SOURCE_FILE_NAME_METADATA_KEY, MANUAL_SOURCE_FILE,
                        LlamaParseDocumentLoader.SOURCE_PARSER_METADATA_KEY, "manual")))
                .toList();

        if (documents.isEmpty()) {
            return 0;
        }

        addDocumentsInBatches(documents);
        return documents.size();
    }

    public IngestFileResult ingestFile(MultipartFile file) {
        List<Document> documents = llamaParseDocumentLoader.loadAndSplit(file);
        if (documents.isEmpty()) {
            return new IngestFileResult(resolveFileName(file), 0, 0);
        }

        addDocumentsInBatches(documents);
        return new IngestFileResult(resolveFileName(file), documents.size(), documents.size());
    }

    private void addDocumentsInBatches(List<Document> documents) {
        int total = documents.size();
        for (int start = 0; start < total; start += embeddingBatchSize) {
            int end = Math.min(start + embeddingBatchSize, total);
            vectorStore.add(documents.subList(start, end));
        }
    }

    public RagAnswer ask(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        int resolvedTopK = (topK == null || topK <= 0) ? 4 : topK;

        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(resolvedTopK)
                .build();

        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build();

        ChatClientResponse response = chatClient.prompt()
                .advisors(questionAnswerAdvisor)
                .user(question)
                .call()
                .chatClientResponse();

        String answer = "";
        if (response != null
                && response.chatResponse() != null
                && response.chatResponse().getResult() != null
                && response.chatResponse().getResult().getOutput() != null) {
            answer = response.chatResponse().getResult().getOutput().getText();
        }

        List<Document> retrievedDocuments = extractRetrievedDocuments(response);
        List<String> sourceFiles = extractSourceFiles(retrievedDocuments);

        return new RagAnswer(answer, retrievedDocuments.size(), sourceFiles);
    }

    private List<Document> extractRetrievedDocuments(ChatClientResponse response) {
        if (response == null || response.context() == null) {
            return List.of();
        }

        Object retrieved = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrieved instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return documents;
    }

    private List<String> extractSourceFiles(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        return documents.stream()
                .map(Document::getMetadata)
                .filter(Objects::nonNull)
                .map(metadata -> metadata.get(LlamaParseDocumentLoader.SOURCE_FILE_NAME_METADATA_KEY))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String resolveFileName(MultipartFile file) {
        if (file == null || !StringUtils.hasText(file.getOriginalFilename())) {
            return "uploaded-file";
        }
        return file.getOriginalFilename();
    }

    public record RagAnswer(String answer, int hitCount, List<String> sourceFiles) {
    }

    public record IngestFileResult(String fileName, int ingested, int chunkCount) {
    }
}
