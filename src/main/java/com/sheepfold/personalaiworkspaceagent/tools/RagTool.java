package com.sheepfold.personalaiworkspaceagent.tools;

import com.sheepfold.personalaiworkspaceagent.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RagTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);

    private final ObjectProvider<RagService> ragServiceProvider;

    public RagTool(ObjectProvider<RagService> ragServiceProvider) {
        this.ragServiceProvider = ragServiceProvider;
    }

    @Tool(name = "rag_ask", description = "基于知识库进行 RAG 问答，返回答案与命中来源文件。")
    public Map<String, Object> ask(
            @ToolParam(description = "用户问题", required = true) String question,
            @ToolParam(description = "召回条数，默认 4", required = false) Integer topK) {
        if (!StringUtils.hasText(question)) {
            return Map.of(
                    "success", false,
                    "message", "question 不能为空");
        }

        int resolvedTopK = (topK == null || topK <= 0) ? 4 : topK;
        log.info("RAG 工具调用: questionLength={}, topK={}", question.length(), resolvedTopK);

        RagService ragService = ragServiceProvider.getObject();
        RagService.RagAnswer answer = ragService.ask(question, resolvedTopK);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("answer", answer.answer());
        result.put("hitCount", answer.hitCount());
        result.put("sourceFiles", answer.sourceFiles());
        log.info("RAG 工具完成: hitCount={}, sourceFiles={}", answer.hitCount(), answer.sourceFiles().size());
        return result;
    }
}
