package com.sheepfold.personalaiworkspaceagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final ChatMemory chatMemory;

    public MemoryService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public String resolveSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return ChatMemory.DEFAULT_CONVERSATION_ID;
        }
        return sessionId.trim();
    }

    public List<Message> getConversationMessages(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        return List.copyOf(chatMemory.get(resolvedSessionId));
    }

    public List<MemoryEntry> getEntries(String sessionId) {
        List<Message> messages = getConversationMessages(sessionId);
        if (messages.isEmpty()) {
            return List.of();
        }

        List<MemoryEntry> entries = new ArrayList<>(messages.size());
        for (Message message : messages) {
            MemoryRole role = toMemoryRole(message.getMessageType());
            if (role == null || !StringUtils.hasText(message.getText())) {
                continue;
            }
            entries.add(new MemoryEntry(role, message.getText(), null));
        }
        return entries;
    }

    public void appendUserMessage(String sessionId, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        chatMemory.add(resolveSessionId(sessionId), new UserMessage(content.trim()));
    }

    public void appendAssistantMessage(String sessionId, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        chatMemory.add(resolveSessionId(sessionId), new AssistantMessage(content.trim()));
    }

    public int size(String sessionId) {
        return getConversationMessages(sessionId).size();
    }

    public boolean clear(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        boolean existed = !chatMemory.get(resolvedSessionId).isEmpty();
        chatMemory.clear(resolvedSessionId);
        if (existed) {
            log.info("短期记忆已清空: sessionId={}", resolvedSessionId);
        }
        return existed;
    }

    private MemoryRole toMemoryRole(MessageType messageType) {
        if (messageType == MessageType.USER) {
            return MemoryRole.USER;
        }
        if (messageType == MessageType.ASSISTANT) {
            return MemoryRole.ASSISTANT;
        }
        return null;
    }
}
