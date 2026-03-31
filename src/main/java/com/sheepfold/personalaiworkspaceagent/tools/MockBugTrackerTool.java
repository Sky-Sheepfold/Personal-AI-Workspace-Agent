package com.sheepfold.personalaiworkspaceagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class MockBugTrackerTool {

    private static final Logger log = LoggerFactory.getLogger(MockBugTrackerTool.class);

    private static final Map<String, BugRecord> BUGS = Map.of(
            "BUG-101",
            new BugRecord("Safari 上登录失败", "未解决", "P1", "alice", "2026-03-29T10:12:00+08:00", "mock-jira"),
            "BUG-102",
            new BugRecord("导出报表超时", "处理中", "P2", "bob", "2026-03-30T09:05:00+08:00",
                    "mock-jira"),
            "ZENTAO-88",
            new BugRecord("附件预览异常", "已解决", "P2", "charlie", "2026-03-27T18:22:00+08:00",
                    "mock-zentao"),
            "JIRA-77",
            new BugRecord("iOS 上页面布局错位", "已关闭", "P3", "diana", "2026-03-26T15:40:00+08:00",
                    "mock-jira"));

    @Tool(name = "mock_bug_status", description = "查询虚构的 Jira/禅道缺陷状态。")
    public Map<String, Object> getBugStatus(
            @ToolParam(description = "缺陷编号，例如 BUG-101 或 ZENTAO-88", required = true) String issueKey) {
        String normalized = normalizeKey(issueKey);
        log.info("缺陷查询请求: issueKey={}, normalized={}", issueKey, normalized);
        if (normalized.isBlank()) {
            return Map.of(
                    "found", false,
                    "message", "issueKey 不能为空");
        }

        BugRecord record = BUGS.get(normalized);
        if (record == null) {
            log.warn("未找到模拟缺陷: {}", normalized);
            return Map.of(
                    "found", false,
                    "issueKey", normalized,
                    "message", "未找到模拟缺陷");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("issueKey", normalized);
        result.put("summary", record.summary());
        result.put("status", record.status());
        result.put("priority", record.priority());
        result.put("assignee", record.assignee());
        result.put("updatedAt", record.updatedAt());
        result.put("tracker", record.tracker());
        log.info("缺陷查询命中: {} status={} priority={}", normalized, record.status(), record.priority());
        return result;
    }

    private String normalizeKey(String issueKey) {
        if (issueKey == null) {
            return "";
        }
        return issueKey.trim().toUpperCase(Locale.ROOT);
    }

    private record BugRecord(String summary, String status, String priority, String assignee, String updatedAt,
            String tracker) {
    }
}
