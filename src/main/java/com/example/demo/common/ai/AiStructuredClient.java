package com.example.demo.common.ai;

import com.example.demo.common.util.AiJsonUtils;
import com.example.demo.modules.llmprovider.core.LlmProviderProperties.ProviderConfig;
import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 通用 AI 结构化输出调用与安全兜底执行组件（全面接入大模型网关多模型自动故障转移）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStructuredClient {

    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 调用大模型并结构化解析为指定类型对象，若发生任何异常自动安全降级触发 fallback
     */
    public <T> T callForStructured(String prompt, TypeReference<T> typeRef, Supplier<T> fallbackSupplier) {
        long startTime = System.currentTimeMillis();
        String defaultProviderId = "unknown";
        String modelName = "unknown";
        try {
            defaultProviderId = llmProviderRegistry.resolveDefaultProviderId();
            ProviderConfig config = llmProviderRegistry.getDefaultProviderConfig();
            if (config != null && config.getModel() != null) {
                modelName = config.getModel();
            }
        } catch (Exception ignored) {}

        try {
            // 🌟 核心接入：使用大模型网关多模型自动故障转移 (Failover)
            ChatResponse chatResponse = llmProviderRegistry.executeWithFailover(null, client ->
                    client.prompt().user(prompt).call().chatResponse()
            );
            long duration = System.currentTimeMillis() - startTime;

            String responseText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText() : "";

            Usage usage = (chatResponse != null && chatResponse.getMetadata() != null)
                    ? chatResponse.getMetadata().getUsage() : null;
            long promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0;
            long completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
            long totalTokens = usage != null && usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : (promptTokens + completionTokens);

            log.info("""
                    
                    ==================== 🤖 [AI 结构化调用监控·网关高可用保护] ====================
                    ⚙️ 供应商: {} | 模型: {} | 耗时: {}ms
                    📊 Token 消耗: Prompt={}, Completion={}, Total={}
                    📝 大模型响应预览: {}
                    ==============================================================================""",
                    defaultProviderId, modelName, duration,
                    promptTokens, completionTokens, totalTokens,
                    responseText.length() > 300 ? responseText.substring(0, 300) + "..." : responseText
            );

            return AiJsonUtils.parse(responseText, typeRef, objectMapper);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("❌ AI 结构化调用/解析失败 (耗时 {}ms)，已触发安全兜底: error={}", duration, e.getMessage());
            return fallbackSupplier.get();
        }
    }

    /**
     * 支持传入 Class 的重载版本
     */
    public <T> T callForStructured(String prompt, Class<T> clazz, Supplier<T> fallbackSupplier) {
        long startTime = System.currentTimeMillis();
        String defaultProviderId = "unknown";
        String modelName = "unknown";
        try {
            defaultProviderId = llmProviderRegistry.resolveDefaultProviderId();
            ProviderConfig config = llmProviderRegistry.getDefaultProviderConfig();
            if (config != null && config.getModel() != null) {
                modelName = config.getModel();
            }
        } catch (Exception ignored) {}

        try {
            // 🌟 核心接入：使用大模型网关多模型自动故障转移 (Failover)
            ChatResponse chatResponse = llmProviderRegistry.executeWithFailover(null, client ->
                    client.prompt().user(prompt).call().chatResponse()
            );
            long duration = System.currentTimeMillis() - startTime;

            String responseText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText() : "";

            Usage usage = (chatResponse != null && chatResponse.getMetadata() != null)
                    ? chatResponse.getMetadata().getUsage() : null;
            long promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0;
            long completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
            long totalTokens = usage != null && usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : (promptTokens + completionTokens);

            log.info("""
                    
                    ==================== 🤖 [AI 结构化调用监控·网关高可用保护] ====================
                    ⚙️ 供应商: {} | 模型: {} | 耗时: {}ms
                    📊 Token 消耗: Prompt={}, Completion={}, Total={}
                    📝 大模型响应预览: {}
                    ==============================================================================""",
                    defaultProviderId, modelName, duration,
                    promptTokens, completionTokens, totalTokens,
                    responseText.length() > 300 ? responseText.substring(0, 300) + "..." : responseText
            );

            return AiJsonUtils.parse(responseText, clazz, objectMapper);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("❌ AI 结构化调用/解析失败 (耗时 {}ms)，已触发安全兜底: error={}", duration, e.getMessage());
            return fallbackSupplier.get();
        }
    }
}