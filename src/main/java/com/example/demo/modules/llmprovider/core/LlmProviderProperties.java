package com.example.demo.modules.llmprovider.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 多供应商配置绑定类（app.ai.*）
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {

    /** 默认供应商 id */
    private String defaultProvider;

    /** 供应商配置表：key = 供应商 id（deepseek、glm…） */
    private Map<String, ProviderConfig> providers;

    /**
     * 单个供应商的连接配置
     */
    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private Boolean supportsEmbedding;
    }
}