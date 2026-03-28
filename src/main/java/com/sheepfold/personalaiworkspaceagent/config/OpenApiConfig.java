package com.sheepfold.personalaiworkspaceagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI personalAiWorkspaceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Personal AI Workspace Agent API")
                .description("MVP RAG and vector debug APIs")
                .version("v1")
                .contact(new Contact().name("Personal AI Workspace Agent")));
    }

    @Bean
    public GroupedOpenApi ragGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("rag")
                .pathsToMatch("/api/rag/**")
                .build();
    }
}
