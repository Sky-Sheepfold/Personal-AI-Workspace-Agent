package com.sheepfold.personalaiworkspaceagent.tools;

import com.sheepfold.personalaiworkspaceagent.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ToolCallingService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingService.class);

    private final ChatClient chatClient;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final ToolCallAdvisor toolCallAdvisor;
    private final ToolCallbackProvider toolCallbackProvider;
    private final MemoryService memoryService;

    public ToolCallingService(ChatClient.Builder chatClientBuilder,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            ToolCallAdvisor toolCallAdvisor,
            ToolCallbackProvider toolCallbackProvider,
            MemoryService memoryService) {
        this.chatClient = chatClientBuilder.build();
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolCallbackProvider = toolCallbackProvider;
        this.memoryService = memoryService;
    }

    public ToolCallingResult ask(String sessionId, String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question must not be blank");
        }

        String resolvedSessionId = memoryService.resolveSessionId(sessionId);
        int historySize = memoryService.size(resolvedSessionId);

        log.info("工具调用开始: sessionId={}, historySize={}, questionLength={}",
                resolvedSessionId,
                historySize,
                question.length());

        ChatClientResponse response = chatClient.prompt()
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, resolvedSessionId)
                        .advisors(messageChatMemoryAdvisor, toolCallAdvisor))
                .toolCallbacks(toolCallbackProvider)
                .user(question)
                .call()
                .chatClientResponse();

        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            log.warn("工具调用返回空响应");
            int memorySize = memoryService.size(resolvedSessionId);
            return new ToolCallingResult(resolvedSessionId, "", memorySize);
        }

        String answer = response.chatResponse().getResult().getOutput().getText();

        int memorySize = memoryService.size(resolvedSessionId);
        log.info("工具调用完成: sessionId={}, answerLength={}, memorySize={}",
                resolvedSessionId,
                answer == null ? 0 : answer.length(),
                memorySize);
        return new ToolCallingResult(resolvedSessionId, answer, memorySize);
    }

    public record ToolCallingResult(String sessionId, String answer, int memorySize) {
    }
}
