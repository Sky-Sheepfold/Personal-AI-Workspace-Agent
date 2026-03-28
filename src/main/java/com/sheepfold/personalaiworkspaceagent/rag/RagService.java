package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final String RAG_PROMPT = """
            你是一个严谨的知识助手。
            请只根据提供的上下文回答问题。
            如果上下文不足，请明确回答“我不知道”。

            上下文:
            {context}

            问题:
            {question}
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public int ingest(RagIngestRequest request) {
        if (request == null || request.chunks() == null || request.chunks().isEmpty()) {
            return 0;
        }

        List<Document> documents = request.chunks().stream()
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .map(Document::new)
                .toList();


        if (documents.isEmpty()) {
            return 0;
        }

        vectorStore.add(documents);
        return documents.size();
    }

    public RagAnswer ask(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        int resolvedTopK = (topK == null || topK <= 0) ? 4 : topK;

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(resolvedTopK)
                .build());

        String context = hits == null || hits.isEmpty()
                ? ""
                : hits.stream().map(Document::getText).reduce((a, b) -> a + "\n\n" + b).orElse("");

        String answer = chatClient.prompt()
                .user(user -> user
                        .text(RAG_PROMPT)
                        .param("context", context)
                        .param("question", question))
                .call()
                .content();

        int hitCount = hits == null ? 0 : hits.size();
        return new RagAnswer(answer, hitCount);
    }

    public record RagAnswer(String answer, int hitCount) {
    }
}
