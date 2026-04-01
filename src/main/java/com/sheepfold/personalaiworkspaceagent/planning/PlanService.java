package com.sheepfold.personalaiworkspaceagent.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final int LOG_PREVIEW_MAX = 100;

    private final ChatClient plannerClient;
    private final ObjectMapper objectMapper;
    private final int maxPlanSteps;
    private final Map<String, PlanEntry> latestPlans = new ConcurrentHashMap<>();

    public PlanService(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            @Value("${agent.planning.max-steps:6}") int maxPlanSteps) {
        this.plannerClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.maxPlanSteps = Math.max(2, maxPlanSteps);
    }

    public PlanEntry createPlan(String sessionId, String goal) {
        return createPlan(sessionId, goal, null);
    }

    public PlanEntry createPlan(String sessionId, String goal, String traceId) {
        String trace = resolvedTraceId(traceId);
        String resolvedGoal = normalizeGoal(goal);
        log.info("[ReAct][{}][Thought] 规划器收到目标: goalPreview={}", trace, preview(resolvedGoal));

        String rawPlan = generateRawPlan(resolvedGoal, trace);

        PlanEntry parsed = parsePlan(sessionId, resolvedGoal, rawPlan, trace)
                .orElseGet(() -> fallbackPlan(sessionId, resolvedGoal, trace));

        PlanEntry started = markFirstStepInProgress(parsed);
        latestPlans.put(sessionId, started);
        log.info("[ReAct][{}][Thought] 规划完成: sessionId={}, steps={}, summary={}",
                trace,
                sessionId,
                started.steps().size(),
                preview(started.summary()));
        return started;
    }

    public Optional<PlanEntry> getLatestPlan(String sessionId) {
        return Optional.ofNullable(latestPlans.get(sessionId));
    }

    public PlanEntry markAllCompleted(String sessionId) {
        return markAllCompleted(sessionId, null);
    }

    public PlanEntry markAllCompleted(String sessionId, String traceId) {
        String trace = resolvedTraceId(traceId);
        PlanEntry current = latestPlans.get(sessionId);
        if (current == null) {
            log.warn("[ReAct][{}][Thought] 未找到计划，无法标记完成: sessionId={}", trace, sessionId);
            return null;
        }

        List<PlanStep> completed = new ArrayList<>(current.steps().size());
        for (PlanStep step : current.steps()) {
            completed.add(new PlanStep(step.index(), step.title(), step.description(), PlanStatus.COMPLETED));
        }

        PlanEntry updated = new PlanEntry(
                current.sessionId(),
                current.goal(),
                current.summary(),
                List.copyOf(completed),
                current.createdAt(),
                Instant.now());
        latestPlans.put(sessionId, updated);
        log.info("[ReAct][{}][Thought] 计划步骤已全部完成: sessionId={}, steps={}",
                trace,
                sessionId,
                updated.steps().size());
        return updated;
    }

    public String buildExecutionInstruction(PlanEntry planEntry) {
        if (planEntry == null || planEntry.steps() == null || planEntry.steps().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("你是超级智能体，请按以下计划执行任务，并在最终回答中给出结果：\n");
        builder.append("计划摘要：").append(planEntry.summary()).append("\n");
        for (PlanStep step : planEntry.steps()) {
            builder.append(step.index())
                    .append(". ")
                    .append(step.title())
                    .append(" - ")
                    .append(step.description())
                    .append(" [")
                    .append(step.status())
                    .append("]\n");
        }
        return builder.toString();
    }

    private String generateRawPlan(String goal, String traceId) {
        long startedAt = System.currentTimeMillis();
        try {
            ChatClientResponse response = plannerClient.prompt()
                    .system(buildPlanPrompt())
                    .user("用户目标: " + goal)
                    .call()
                    .chatClientResponse();

            if (response == null
                    || response.chatResponse() == null
                    || response.chatResponse().getResult() == null
                    || response.chatResponse().getResult().getOutput() == null
                    || !StringUtils.hasText(response.chatResponse().getResult().getOutput().getText())) {
                log.warn("[ReAct][{}][Thought] 规划器返回空内容，进入降级流程", traceId);
                return "";
            }

            String output = response.chatResponse().getResult().getOutput().getText();
            long costMs = System.currentTimeMillis() - startedAt;
            log.info("[ReAct][{}][Thought] 规划器返回成功: outputLength={}, costMs={}",
                    traceId,
                    output.length(),
                    costMs);
            return output;
        } catch (Exception ex) {
            log.warn("[ReAct][{}][Thought] 生成规划失败，使用降级规划: {}", traceId, ex.getMessage());
            return "";
        }
    }

    private Optional<PlanEntry> parsePlan(String sessionId, String goal, String rawPlan, String traceId) {
        if (!StringUtils.hasText(rawPlan)) {
            return Optional.empty();
        }

        String json = extractJson(rawPlan);
        try {
            JsonNode root = objectMapper.readTree(json);
            String summary = textOrDefault(root.path("summary"), "执行用户目标");
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                return Optional.empty();
            }

            List<PlanStep> steps = new ArrayList<>();
            int index = 1;
            for (JsonNode node : stepsNode) {
                if (index > maxPlanSteps) {
                    break;
                }

                String title = textOrDefault(node.path("title"), "步骤" + index);
                String description = textOrDefault(node.path("description"), "执行步骤" + index);
                steps.add(new PlanStep(index, title, description, PlanStatus.PENDING));
                index++;
            }

            if (steps.isEmpty()) {
                return Optional.empty();
            }

            Instant now = Instant.now();
            log.info("[ReAct][{}][Thought] 规划解析成功: steps={}, summary={}",
                    traceId,
                    steps.size(),
                    preview(summary));
            return Optional.of(new PlanEntry(sessionId, goal, summary, List.copyOf(steps), now, now));
        } catch (Exception ex) {
            log.warn("[ReAct][{}][Thought] 解析规划 JSON 失败，使用降级规划: {}", traceId, ex.getMessage());
            return Optional.empty();
        }
    }

    private PlanEntry fallbackPlan(String sessionId, String goal, String traceId) {
        Instant now = Instant.now();
        List<PlanStep> steps = List.of(
                new PlanStep(1, "理解目标", "澄清任务边界与输出要求", PlanStatus.PENDING),
                new PlanStep(2, "选择工具", "根据目标调用最合适的工具能力", PlanStatus.PENDING),
                new PlanStep(3, "生成答案", "整合信息并给出结构化结果", PlanStatus.PENDING));
        log.warn("[ReAct][{}][Thought] 启用降级计划: steps=3", traceId);
        return new PlanEntry(sessionId, goal, "基于用户目标执行三阶段流程", steps, now, now);
    }

    private PlanEntry markFirstStepInProgress(PlanEntry planEntry) {
        if (planEntry.steps() == null || planEntry.steps().isEmpty()) {
            return planEntry;
        }

        List<PlanStep> updated = new ArrayList<>(planEntry.steps().size());
        for (PlanStep step : planEntry.steps()) {
            PlanStatus status = step.index() == 1 ? PlanStatus.IN_PROGRESS : step.status();
            updated.add(new PlanStep(step.index(), step.title(), step.description(), status));
        }

        return new PlanEntry(
                planEntry.sessionId(),
                planEntry.goal(),
                planEntry.summary(),
                List.copyOf(updated),
                planEntry.createdAt(),
                Instant.now());
    }

    private String normalizeGoal(String goal) {
        if (!StringUtils.hasText(goal)) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        return goal.trim();
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        String text = node == null ? "" : node.asText("").trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                trimmed = trimmed.substring(firstNewLine + 1, lastFence).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildPlanPrompt() {
        return """
                你是一个任务规划助手。请根据用户目标生成可执行计划。
                只输出 JSON，不要输出 markdown，不要输出解释。
                JSON 格式如下：
                {
                    \"summary\": \"一句话总结\",
                    \"steps\": [
                        {\"title\": \"步骤标题\", \"description\": \"步骤说明\"}
                    ]
                }
                约束：
                1) steps 数量 2 到 %d。
                2) title 简洁，description 不超过 40 个字。
                3) 保持执行顺序清晰。
                """.formatted(maxPlanSteps);
    }

    private String resolvedTraceId(String traceId) {
        return StringUtils.hasText(traceId) ? traceId : "-";
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
