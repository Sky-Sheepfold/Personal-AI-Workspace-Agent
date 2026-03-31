package com.sheepfold.personalaiworkspaceagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ToolCallingService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingService.class);

    private final ChatClient chatClient;
    private final ToolCallAdvisor toolCallAdvisor;
    private final ToolCallbackProvider toolCallbackProvider;

    public ToolCallingService(ChatClient.Builder chatClientBuilder,
            ToolCallAdvisor toolCallAdvisor,
            ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClientBuilder.build();
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    public String ask(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question must not be blank");
        }

        log.info("工具调用开始: questionLength={}", question.length());

        ChatClientResponse response = chatClient.prompt()
                .advisors(toolCallAdvisor)
                .toolCallbacks(toolCallbackProvider)
                .user(question)
                .call()
                .chatClientResponse();

        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            log.warn("工具调用返回空响应");
            return "";
        }

        String answer = response.chatResponse().getResult().getOutput().getText();
        log.info("工具调用完成: answerLength={}", answer == null ? 0 : answer.length());
        return answer;
    }
}
