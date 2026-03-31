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
                .title("个人 AI 工作台智能体接口文档")
                .description("MVP RAG 与向量调试接口")
                .version("v1")
                .contact(new Contact().name("个人 AI 工作台智能体")));
    }

    @Bean
    public GroupedOpenApi ragGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("rag")
                .pathsToMatch("/api/rag/**")
                .build();
    }
}
