package com.example.demo.common.config;

import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {
    @Bean
    public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {

        return registry.getDefaultEmbeddingModel();
    }
}