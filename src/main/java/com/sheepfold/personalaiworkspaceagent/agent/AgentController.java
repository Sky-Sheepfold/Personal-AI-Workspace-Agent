package com.sheepfold.personalaiworkspaceagent.agent;

import com.sheepfold.personalaiworkspaceagent.agent.dto.AgentChatRequest;
import com.sheepfold.personalaiworkspaceagent.agent.dto.AgentChatResponse;
import com.sheepfold.personalaiworkspaceagent.memory.MemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@Tag(name = "SuperAgent", description = "超级智能体统一入口")
public class AgentController {

    private final AgentService agentService;
    private final MemoryService memoryService;

    public AgentController(AgentService agentService, MemoryService memoryService) {
        this.agentService = agentService;
        this.memoryService = memoryService;
    }

    @PostMapping("/chat")
    @Operation(summary = "超级智能体对话", description = "自动规划 + 工具调用 + 记忆协同")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        String sessionId = request == null ? null : request.sessionId();
        String message = request == null ? null : request.message();
        return agentService.chat(sessionId, message);
    }

    @GetMapping("/plans/{sessionId}")
    @Operation(summary = "查看会话最新计划", description = "返回指定会话最近一次规划结果")
    public Map<String, Object> latestPlan(
            @Parameter(description = "会话 ID") @PathVariable String sessionId) {
        String resolvedSessionId = memoryService.resolveSessionId(sessionId);
        return agentService.latestPlan(resolvedSessionId)
                .<Map<String, Object>>map(plan -> Map.of(
                        "sessionId", resolvedSessionId,
                        "hasPlan", true,
                        "plan", plan))
                .orElseGet(() -> Map.of(
                        "sessionId", resolvedSessionId,
                        "hasPlan", false));
    }
}
