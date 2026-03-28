package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagAskRequest;
import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
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
