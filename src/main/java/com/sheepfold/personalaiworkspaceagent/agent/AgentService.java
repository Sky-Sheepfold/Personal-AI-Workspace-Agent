package com.sheepfold.personalaiworkspaceagent.agent;

import com.sheepfold.personalaiworkspaceagent.agent.dto.AgentChatResponse;
import com.sheepfold.personalaiworkspaceagent.memory.MemoryService;
import com.sheepfold.personalaiworkspaceagent.planning.PlanEntry;
import com.sheepfold.personalaiworkspaceagent.planning.PlanService;
import com.sheepfold.personalaiworkspaceagent.tools.ToolCallingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String REACT_TRACE_MDC_KEY = "reactTraceId";
    private static final int LOG_PREVIEW_MAX = 120;

    private final PlanService planService;
    private final ToolCallingService toolCallingService;
    private final MemoryService memoryService;

    public AgentService(PlanService planService,
            ToolCallingService toolCallingService,
            MemoryService memoryService) {
        this.planService = planService;
        this.toolCallingService = toolCallingService;
        this.memoryService = memoryService;
    }

    public AgentChatResponse chat(String sessionId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message must not be blank");
        }

        String resolvedSessionId = memoryService.resolveSessionId(sessionId);
        String traceId = newTraceId();
        String previousTraceId = MDC.get(REACT_TRACE_MDC_KEY);
        MDC.put(REACT_TRACE_MDC_KEY, traceId);

        long startedAt = System.currentTimeMillis();
        try {
            log.info("[ReAct][{}][Input] sessionId={}, messagePreview={}",
                    traceId,
                    resolvedSessionId,
                    preview(message));

            log.info("[ReAct][{}][Thought] 开始生成执行计划", traceId);
            PlanEntry plan = planService.createPlan(resolvedSessionId, message, traceId);
            String executionInstruction = planService.buildExecutionInstruction(plan);
            log.info("[ReAct][{}][Thought] 计划生成完成: summary={}, steps={}",
                    traceId,
                    preview(plan.summary()),
                    plan.steps() == null ? 0 : plan.steps().size());

            log.info("[ReAct][{}][Action] 开始执行工具链", traceId);
            ToolCallingService.ToolCallingResult result = toolCallingService.ask(
                    resolvedSessionId,
                    message,
                    executionInstruction,
                    traceId);

            PlanEntry completedPlan = Optional.ofNullable(planService.markAllCompleted(resolvedSessionId, traceId))
                    .orElse(plan);
            log.info("[ReAct][{}][Observation] 工具链返回: memorySize={}, answerPreview={}",
                    traceId,
                    result.memorySize(),
                    preview(result.answer()));

            long totalCostMs = System.currentTimeMillis() - startedAt;
            log.info("[ReAct][{}][Answer] 响应完成: totalCostMs={}", traceId, totalCostMs);

            return new AgentChatResponse(
                    result.sessionId(),
                    result.memorySize(),
                    completedPlan,
                    result.answer());
        } catch (RuntimeException ex) {
            long totalCostMs = System.currentTimeMillis() - startedAt;
            log.error("[ReAct][{}][Abort] 智能体执行失败: totalCostMs={}, error={}",
                    traceId,
                    totalCostMs,
                    ex.getMessage(),
                    ex);
            throw ex;
        } finally {
            if (previousTraceId == null) {
                MDC.remove(REACT_TRACE_MDC_KEY);
            } else {
                MDC.put(REACT_TRACE_MDC_KEY, previousTraceId);
            }
        }
    }

    public Optional<PlanEntry> latestPlan(String sessionId) {
        return planService.getLatestPlan(memoryService.resolveSessionId(sessionId));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= LOG_PREVIEW_MAX) {
            return normalized;
        }
        return normalized.substring(0, LOG_PREVIEW_MAX) + "...";
    }
}
