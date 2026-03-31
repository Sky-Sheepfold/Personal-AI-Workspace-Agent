package com.sheepfold.personalaiworkspaceagent.memory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/memory")
@Tag(name = "Memory", description = "短期记忆管理接口")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "查看会话记忆", description = "返回指定 session 的短期记忆内容")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        String resolvedSessionId = memoryService.resolveSessionId(sessionId);
        var entries = memoryService.getEntries(resolvedSessionId);
        return Map.of(
                "sessionId", resolvedSessionId,
                "size", entries.size(),
                "entries", entries);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "清空会话记忆", description = "清空指定 session 的短期记忆")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        String resolvedSessionId = memoryService.resolveSessionId(sessionId);
        boolean cleared = memoryService.clear(resolvedSessionId);
        return Map.of(
                "sessionId", resolvedSessionId,
                "cleared", cleared);
    }
}
