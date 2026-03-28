package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagAskRequest;
import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG", description = "RAG ingestion, query and Chroma debug APIs")
public class RagController {

    private final RagService ragService;
    private final ChromaDebugService chromaDebugService;

    public RagController(RagService ragService, ChromaDebugService chromaDebugService) {
        this.ragService = ragService;
        this.chromaDebugService = chromaDebugService;
    }

    @GetMapping("/collections")
    @Operation(summary = "List Chroma collections", description = "List collections under configured tenant and database")
    public Map<String, Object> collections() {
        return chromaDebugService.listCollections();
    }

    @GetMapping("/vectors")
    @Operation(summary = "Browse stored vectors", description = "Paged query for vectors/documents in current Chroma collection")
    public Map<String, Object> vectors(
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "20") Integer limit,
            @Parameter(description = "Offset from the beginning") @RequestParam(defaultValue = "0") Integer offset,
            @Parameter(description = "Include embedding preview when true") @RequestParam(defaultValue = "false") boolean withEmbedding) {
        return chromaDebugService.listVectors(limit, offset, withEmbedding);
    }

    @PostMapping("/ingest")
    @Operation(summary = "Ingest text chunks", description = "Write input chunks into vector store")
    public Map<String, Object> ingest(@RequestBody RagIngestRequest request) {
        int count = ragService.ingest(request);
        return Map.of("ingested", count);
    }

    @PostMapping("/ask")
    @Operation(summary = "RAG ask", description = "Similarity retrieval + LLM answer generation")
    public Map<String, Object> ask(@RequestBody RagAskRequest request) {
        RagService.RagAnswer result = ragService.ask(request.question(), request.topK());
        return Map.of(
                "answer", result.answer(),
                "hitCount", result.hitCount());
    }
}
