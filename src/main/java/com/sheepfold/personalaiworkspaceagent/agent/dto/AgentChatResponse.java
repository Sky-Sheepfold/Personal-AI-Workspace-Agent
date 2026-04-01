package com.sheepfold.personalaiworkspaceagent.agent.dto;

import com.sheepfold.personalaiworkspaceagent.planning.PlanEntry;

public record AgentChatResponse(String sessionId, int memorySize, PlanEntry plan, String answer) {
}
