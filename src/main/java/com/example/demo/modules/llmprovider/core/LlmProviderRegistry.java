package com.example.demo.modules.llmprovider.core;

import com.example.demo.modules.llmprovider.core.LlmProviderProperties.ProviderConfig;
import com.example.demo.modules.llmprovider.mapper.LlmProviderMapper;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 🌟 大模型网关调度中心 (In-Process LLM Gateway & Provider Registry)
 * 
 * 核心能力：
 * 1. 统一网关协议适配：所有供应商（DeepSeek、GLM、通义千问、小米 mimo）统一走 OpenAI 兼容协议
 * 2. 多 API Key 负载均衡轮询：集成 ApiKeyPool，支持逗号分隔多 Key 轮询与限流自动冷冻
 * 3. Token 计量与成本审计：创建 ChatClient 时统一自动挂载 LlmCostMeterAdvisor
 * 4. 主备多模型自动故障转移 (Failover)：某模型 503/429/超时时，0 毫秒无感自动降级到备用模型
 * 5. 动态热插拔与持久化：前端修改配置，数据库持久化存储并 0 毫秒热重载
 */
@Slf4j
@Component
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final ApiKeyPool apiKeyPool;

    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAIClient> openAiClientCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;

    @Autowired(required = false)
    private LlmProviderMapper llmProviderMapper;

    @Autowired
    public LlmProviderRegistry(LlmProviderProperties properties,
                               ApiKeyPool apiKeyPool,
                               @Autowired(required = false) ToolCallingManager toolCallingManager,
                               @Autowired(required = false) ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.apiKeyPool = apiKeyPool;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
    }

    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("🚪 [LlmProviderRegistry] 惰性初始化 provider={} 的 ChatClient（已注入 Token 计费网关）", id);
            return createChatClient(id);
        });
    }

    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveDefaultProviderId());
    }

    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return getDefaultChatClient();
        }
        return getChatClient(providerId);
    }

    public OpenAIClient getOpenAiClient(String providerId) {
        return openAiClientCache.computeIfAbsent(providerId, id -> {
            ProviderConfig config = loadProviderOrThrow(id);
            String activeKey = apiKeyPool.acquireKey(id, config.getApiKey());
            return ApiPathResolver.buildOpenAiClient(config.getBaseUrl(), activeKey);
        });
    }

    public OpenAIClient getDefaultOpenAiClient() {
        return getOpenAiClient(resolveDefaultProviderId());
    }

    public ProviderConfig getDefaultProviderConfig() {
        return loadProviderOrThrow(resolveDefaultProviderId());
    }

    public String resolveDefaultProviderId() {
        if (llmProviderMapper != null) {
            try {
                var all = llmProviderMapper.findAll();
                if (all != null) {
                    for (var e : all) {
                        if (Boolean.TRUE.equals(e.getIsDefault())) {
                            return e.getId();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[LlmProviderRegistry] 查询数据库默认供应商失败，回退到 yml: {}", e.getMessage());
            }
        }

        String defaultProvider = properties != null ? properties.getDefaultProvider() : null;
        if (defaultProvider == null || defaultProvider.isBlank()) {
            return "xiaomi";
        }
        return defaultProvider;
    }

    private ChatClient createChatClient(String providerId) {
        ProviderConfig config = loadProviderOrThrow(providerId);
        ChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        builder.defaultAdvisors(new LlmCostMeterAdvisor(providerId, config.getModel()));
        return builder.build();
    }

    private ChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] 创建 provider={} 的 ChatModel", id);
            return buildChatModel(id);
        });
    }

    private ChatModel buildChatModel(String providerId) {
        ProviderConfig config = loadProviderOrThrow(providerId);
        OpenAIClient openAiClient = getOpenAiClient(providerId);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getModel())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(options)
                .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
                .build();
    }

    // =========================================================================
    // 🛡️ 大模型网关核心调度：高可用主备故障自动转移 (Failover Engine)
    // =========================================================================

    public <T> T executeWithFailover(String preferredProvider, Function<ChatClient, T> callFunction) {
        List<String> failoverChain = getOrderedFailoverList(preferredProvider);
        Exception lastException = null;

        for (String providerId : failoverChain) {
            try {
                ChatClient client = getChatClient(providerId);
                return callFunction.apply(client);
            } catch (Exception e) {
                log.warn("⚠️ [网关·故障转移] 供应商【{}】调用异常（{}），正在自动无感降级到下一个备用模型...",
                        providerId, e.getMessage());
                try {
                    ProviderConfig cfg = loadProviderOrThrow(providerId);
                    apiKeyPool.markKeyCooldown(cfg.getApiKey());
                } catch (Exception ignored) {}
                lastException = e;
            }
        }

        throw new RuntimeException("大模型网关故障转移耗尽：所有可用模型均调用失败，最后报错: " +
                (lastException != null ? lastException.getMessage() : "未知"));
    }

    public Flux<String> executeStreamWithFailover(String preferredProvider, Function<ChatClient, Flux<String>> streamFunction) {
        List<String> failoverChain = getOrderedFailoverList(preferredProvider);
        if (failoverChain.isEmpty()) {
            return Flux.error(new IllegalStateException("没有可用的大模型供应商"));
        }

        return executeStreamRecursive(failoverChain, 0, streamFunction);
    }

    private Flux<String> executeStreamRecursive(List<String> chain, int index, Function<ChatClient, Flux<String>> streamFunction) {
        if (index >= chain.size()) {
            return Flux.error(new RuntimeException("大模型网关流式故障转移耗尽：所有供应商均失败"));
        }

        String providerId = chain.get(index);
        ChatClient client = getChatClient(providerId);

        return streamFunction.apply(client)
                .onErrorResume(e -> {
                    log.warn("⚠️ [网关·流式故障转移] 供应商【{}】异常（{}），正在自动降级到下一备用供应商...",
                            providerId, e.getMessage());
                    return executeStreamRecursive(chain, index + 1, streamFunction);
                });
    }

    public List<String> getOrderedFailoverList(String preferredProvider) {
        String primary = (preferredProvider != null && !preferredProvider.isBlank() && !"default".equalsIgnoreCase(preferredProvider.trim()))
                ? preferredProvider.trim()
                : resolveDefaultProviderId();

        List<String> chain = new ArrayList<>();
        chain.add(primary);

        // 🌟 按用户要求配置的高可用降级顺位：xiaomi ➔ dashscope (千问) ➔ glm (智谱) ➔ deepseek
        List<String> candidates = List.of("xiaomi", "dashscope", "glm", "deepseek");
        for (String cand : candidates) {
            if (!chain.contains(cand) && isProviderAvailable(cand)) {
                chain.add(cand);
            }
        }
        return chain;
    }

    public boolean isProviderAvailable(String providerId) {
        try {
            ProviderConfig cfg = loadProviderOrThrow(providerId);
            return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public ProviderConfig loadProviderOrThrow(String providerId) {
        if (llmProviderMapper != null) {
            try {
                var dbEntity = llmProviderMapper.findById(providerId);
                if (dbEntity != null) {
                    ProviderConfig config = new ProviderConfig();
                    config.setBaseUrl(dbEntity.getBaseUrl());
                    config.setApiKey(dbEntity.getApiKey() != null ? dbEntity.getApiKey().trim() : "");
                    config.setModel(dbEntity.getModel());
                    config.setEmbeddingModel(dbEntity.getEmbeddingModel());
                    config.setEmbeddingDimensions(dbEntity.getEmbeddingDimensions());
                    config.setSupportsEmbedding(dbEntity.getSupportsEmbedding());
                    config.setTemperature(dbEntity.getTemperature());
                    return config;
                }
            } catch (Exception e) {
                log.warn("[LlmProviderRegistry] 读取数据库供应商配置失败，回退到 yml: providerId={}, error={}", providerId, e.getMessage());
            }
        }

        Map<String, ProviderConfig> providers = properties != null ? properties.getProviders() : null;
        ProviderConfig config = providers == null ? null : providers.get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] 供应商配置不存在: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        return config;
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] 创建 provider={} 的 EmbeddingModel", id);
            return createEmbeddingModel(id);
        });
    }

    public String resolveDefaultEmbeddingProviderId() {
        if (llmProviderMapper != null) {
            try {
                var all = llmProviderMapper.findAll();
                if (all != null) {
                    for (var e : all) {
                        if (Boolean.TRUE.equals(e.getIsDefaultEmbedding())) {
                            return e.getId();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[LlmProviderRegistry] 查询数据库默认 Embedding 供应商失败: {}", e.getMessage());
            }
        }

        Map<String, ProviderConfig> providers = properties != null ? properties.getProviders() : null;
        if (providers != null) {
            for (Map.Entry<String, ProviderConfig> entry : providers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().getSupportsEmbedding())) {
                    return entry.getKey();
                }
            }
        }
        return "dashscope";
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderConfig config = loadProviderOrThrow(providerId);
        if (!Boolean.TRUE.equals(config.getSupportsEmbedding())) {
            throw new IllegalArgumentException("Provider " + providerId + " does not support embedding");
        }

        OpenAIClient openAiClient = getOpenAiClient(providerId);

        String embModel = (config.getEmbeddingModel() != null && !config.getEmbeddingModel().isBlank())
                ? config.getEmbeddingModel() : "text-embedding-v3";

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embModel)
                .build();

        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient)
                .options(options)
                .build();
    }

    public void reload() {
        reloadProviders();
    }

    public void reloadProviders() {
        log.info("[LlmProviderRegistry] 开始热重载所有供应商客户端缓存...");
        clientCache.clear();
        chatModelCache.clear();
        openAiClientCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] 供应商客户端缓存已全部清空，将在下次请求时按新配置惰性重建！");
    }
}