package com.example.demo.modules.llmprovider.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大模型供应商持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderEntity {
    private String id;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private Boolean supportsEmbedding;
    private Double temperature;
    private Boolean isDefault;
    private Boolean isDefaultEmbedding;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
