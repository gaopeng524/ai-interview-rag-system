package com.example.demo.modules.llmprovider.model.dto;

import lombok.Builder;
import java.util.List;

/**
 * 大模型供应商（LLM Provider）所有交互数据结构统一收敛类
 */
public final class LlmProviderDTO {

    private LlmProviderDTO() {}

    /**
     * 供应商信息详情 / 列表项
     */
    @Builder
    public record ProviderItem(
        String id,
        String baseUrl,
        String maskedApiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature,
        boolean defaultChatProvider,
        boolean defaultEmbeddingProvider
    ) {}

    /**
     * 创建供应商请求
     */
    public record CreateRequest(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        Boolean supportsEmbedding,
        Double temperature
    ) {}

    /**
     * 更新供应商请求
     */
    public record UpdateRequest(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        Boolean supportsEmbedding,
        Double temperature
    ) {}

    /**
     * 默认供应商配置
     */
    public record DefaultProviderRequest(
        String defaultProvider,
        String defaultEmbeddingProvider
    ) {
        public DefaultProviderRequest(String defaultProvider) {
            this(defaultProvider, null);
        }
    }

    /**
     * 连通性测试结果
     */
    @Builder
    public record TestResult(
        boolean success,
        String message,
        String model
    ) {}

    /**
     * 语音识别配置（精简版）
     */
    @Builder
    public record AsrConfig(
        String model,
        String language
    ) {}

    /**
     * 语音合成配置（精简版）
     */
    @Builder
    public record TtsConfig(
        String model,
        String voice
    ) {}

    /**
     * 🚪 大模型网关运行期实时状态看板与 Token 账单 VO
     */
    @Builder
    public record GatewayStatusVO(
        String defaultProvider,
        String defaultModel,
        List<String> availableProviders,
        List<String> failoverChain,
        long globalPromptTokens,
        long globalCompletionTokens,
        long globalTotalTokens,
        double globalTotalCostYuan,
        boolean isHealthy
    ) {}

    /**
     * 🚪 大模型网关高可用测试请求
     */
    public record GatewayTestRequest(
        String prompt,
        String preferredProvider
    ) {}

    /**
     * 🚪 大模型网关高可用测试结果
     */
    @Builder
    public record GatewayTestResponse(
        boolean success,
        String targetProvider,
        String actualProvider,
        boolean failoverOccurred,
        String responseContent,
        long durationMs,
        String errorMessage
    ) {}
}
