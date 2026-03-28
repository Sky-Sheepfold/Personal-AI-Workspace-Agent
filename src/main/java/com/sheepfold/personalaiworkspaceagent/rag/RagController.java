package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagAskRequest;
import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final ChromaDebugService chromaDebugService;

    public RagController(RagService ragService, ChromaDebugService chromaDebugService) {
        this.ragService = ragService;
        this.chromaDebugService = chromaDebugService;
    }

    @GetMapping("/collections")
    public Map<String, Object> collections() {
        return chromaDebugService.listCollections();
    }

    @GetMapping("/vectors")
    public Map<String, Object> vectors(
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset,
            @RequestParam(defaultValue = "false") boolean withEmbedding) {
        return chromaDebugService.listVectors(limit, offset, withEmbedding);
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody RagIngestRequest request) {
        int count = ragService.ingest(request);
        return Map.of("ingested", count);
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody RagAskRequest request) {
        RagService.RagAnswer result = ragService.ask(request.question(), request.topK());
        return Map.of(
                "answer", result.answer(),
                "hitCount", result.hitCount());
    }
}
