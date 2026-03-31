package com.sheepfold.personalaiworkspaceagent.tools;

import com.sheepfold.personalaiworkspaceagent.tools.dto.ToolsAskRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@Tag(name = "Tools", description = "工具调用接口")
public class ToolsController {

    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    private final ToolCallingService toolCallingService;

    public ToolsController(ToolCallingService toolCallingService) {
        this.toolCallingService = toolCallingService;
    }

    @PostMapping("/ask")
    @Operation(summary = "工具调用问答", description = "仅触发工具调用，不走 RAG")
    public Map<String, Object> ask(@RequestBody ToolsAskRequest request) {
        String question = request == null ? null : request.question();
        String sessionId = request == null ? null : request.sessionId();

        if (request == null) {
            log.warn("/api/tools/ask 收到空请求体");
        } else {
            log.info("/api/tools/ask 请求: sessionId={}, questionLength={}",
                    sessionId,
                    question == null ? 0 : question.length());
        }

        ToolCallingService.ToolCallingResult result = toolCallingService.ask(sessionId, question);
        return Map.of(
                "sessionId", result.sessionId(),
                "memorySize", result.memorySize(),
                "answer", result.answer());
    }
}
