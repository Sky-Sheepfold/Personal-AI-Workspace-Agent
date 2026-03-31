package com.sheepfold.personalaiworkspaceagent.config;

import com.sheepfold.personalaiworkspaceagent.tools.MockBugTrackerTool;
import com.sheepfold.personalaiworkspaceagent.tools.RagTool;
import com.sheepfold.personalaiworkspaceagent.tools.TimeAwarenessTool;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolCallingConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(TimeAwarenessTool timeAwarenessTool,
            MockBugTrackerTool mockBugTrackerTool,
            RagTool ragTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(timeAwarenessTool, mockBugTrackerTool, ragTool)
                .build();
    }

    @Bean
    public ToolCallAdvisor toolCallAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();
    }
}
