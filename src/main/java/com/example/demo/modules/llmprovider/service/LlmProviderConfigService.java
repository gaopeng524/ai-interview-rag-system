package com.example.demo.modules.llmprovider.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.llmprovider.core.LlmCostMeterAdvisor;
import com.example.demo.modules.llmprovider.core.LlmProviderProperties;
import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.example.demo.modules.llmprovider.mapper.LlmProviderMapper;
import com.example.demo.modules.llmprovider.model.dto.LlmProviderDTO;
import com.example.demo.modules.llmprovider.model.entity.LlmProviderEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 大模型供应商配置与网关业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private final LlmProviderMapper mapper;
    private final LlmProviderRegistry registry;
    private final LlmProviderProperties properties;

    @PostConstruct
    public void initProvidersFromProperties() {
        if (properties == null || properties.getProviders() == null) {
            return;
        }
        String defaultProviderId = properties.getDefaultProvider();
        properties.getProviders().forEach((id, cfg) -> {
            try {
                LlmProviderEntity existing = mapper.findById(id);
                if (existing == null) {
                    LlmProviderEntity entity = new LlmProviderEntity();
                    entity.setId(id);
                    entity.setBaseUrl(cfg.getBaseUrl());
                    entity.setApiKey(cfg.getApiKey() != null ? cfg.getApiKey() : "");
                    entity.setModel(cfg.getModel());
                    entity.setEmbeddingModel(cfg.getEmbeddingModel());
                    entity.setEmbeddingDimensions(cfg.getEmbeddingDimensions() != null ? cfg.getEmbeddingDimensions() : 1536);
                    entity.setSupportsEmbedding(Boolean.TRUE.equals(cfg.getSupportsEmbedding()));
                    entity.setTemperature(cfg.getTemperature() != null ? cfg.getTemperature() : 0.2);
                    entity.setIsDefault(id.equalsIgnoreCase(defaultProviderId));
                    mapper.insert(entity);
                    log.info("[LlmProviderConfigService] 自动同步 application.yml 供应商到数据库: id={}", id);
                }
            } catch (Exception e) {
                log.warn("[LlmProviderConfigService] 同步供应商配置失败: id={}, error={}", id, e.getMessage());
            }
        });

        if (defaultProviderId != null && !defaultProviderId.isBlank()) {
            LlmProviderEntity def = mapper.findById(defaultProviderId);
            if (def != null && !Boolean.TRUE.equals(def.getIsDefault())) {
                mapper.resetAllDefault();
                mapper.setDefault(defaultProviderId);
                registry.reload();
                log.info("[LlmProviderConfigService] 默认模型已同步设为: {}", defaultProviderId);
            }
        }
    }

    public List<LlmProviderDTO.ProviderItem> listProviders() {
        return mapper.findAll().stream()
                .map(this::toItem)
                .toList();
    }

    public LlmProviderDTO.ProviderItem getProvider(String id) {
        LlmProviderEntity entity = mapper.findById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在: " + id);
        }
        return toItem(entity);
    }

    @Transactional
    public void createProvider(LlmProviderDTO.CreateRequest req) {
        if (mapper.findById(req.id()) != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "供应商ID已存在: " + req.id());
        }

        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(req.id());
        entity.setBaseUrl(properties.requireAllowedEndpoint(req.baseUrl()).toString());
        entity.setApiKey(req.apiKey());
        entity.setModel(req.model());
        entity.setEmbeddingModel(req.embeddingModel());
        entity.setEmbeddingDimensions(req.embeddingDimensions() != null ? req.embeddingDimensions() : 1536);
        entity.setSupportsEmbedding(req.supportsEmbedding() != null ? req.supportsEmbedding() : false);
        entity.setTemperature(req.temperature() != null ? req.temperature() : 0.2);
        entity.setIsDefault(false);

        mapper.insert(entity);
        registry.reload();
    }

    @Transactional
    public void updateProvider(String id, LlmProviderDTO.UpdateRequest req) {
        LlmProviderEntity entity = mapper.findById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在: " + id);
        }

        boolean hasNewKey = req.apiKey() != null && !req.apiKey().isBlank();
        if (req.baseUrl() != null) {
            var endpoint = properties.requireAllowedEndpoint(req.baseUrl());
            String oldUrl = entity.getBaseUrl();
            String oldKey = entity.getApiKey();
            if (oldKey != null && !oldKey.isBlank() && !endpoint.toString().equals(oldUrl)
                    && !sameTrustedHost(oldUrl, endpoint.getHost())
                    && (!hasNewKey || sharesApiKey(oldKey, req.apiKey()))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "更换模型主机时必须配置该主机的 API Key");
            }
            entity.setBaseUrl(endpoint.toString());
        }
        if (hasNewKey) entity.setApiKey(req.apiKey());
        if (req.model() != null) entity.setModel(req.model());
        if (req.embeddingModel() != null) entity.setEmbeddingModel(req.embeddingModel());
        if (req.embeddingDimensions() != null) entity.setEmbeddingDimensions(req.embeddingDimensions());
        if (req.supportsEmbedding() != null) entity.setSupportsEmbedding(req.supportsEmbedding());
        if (req.temperature() != null) entity.setTemperature(req.temperature());

        mapper.update(entity);
        registry.reload();
    }

    private boolean sameTrustedHost(String oldUrl, String host) {
        try {
            return host.equalsIgnoreCase(properties.requireAllowedEndpoint(oldUrl).getHost());
        } catch (BusinessException ex) {
            return false;
        }
    }

    private static boolean sharesApiKey(String oldKeys, String newKeys) {
        var existing = Arrays.stream(oldKeys.split(",")).map(String::trim).filter(key -> !key.isEmpty()).toList();
        return Arrays.stream(newKeys.split(",")).map(String::trim).anyMatch(existing::contains);
    }

    @Transactional
    public void deleteProvider(String id) {
        LlmProviderEntity entity = mapper.findById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在: " + id);
        }
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "默认供应商不能删除");
        }

        mapper.deleteById(id);
        registry.reload();
    }

    public LlmProviderDTO.TestResult testProvider(String id) {
        LlmProviderEntity entity = mapper.findById(id);
        if (entity == null) {
            return LlmProviderDTO.TestResult.builder()
                    .success(false)
                    .message("供应商不存在")
                    .build();
        }

        try {
            ChatClient client = registry.getChatClient(id);
            String response = client.prompt()
                    .user("你好，请只回复数字 1")
                    .call()
                    .content();

            boolean success = response != null && !response.isBlank();
            return LlmProviderDTO.TestResult.builder()
                    .success(success)
                    .message(success ? "连通成功: " + response.trim() : "响应为空")
                    .model(entity.getModel())
                    .build();
        } catch (Exception e) {
            log.warn("[LlmProviderConfigService] 连通性测试失败: id={}, error={}", id, e.getMessage());
            return LlmProviderDTO.TestResult.builder()
                    .success(false)
                    .message("连接失败: " + e.getMessage())
                    .model(entity.getModel())
                    .build();
        }
    }

    public void reloadProviders() {
        registry.reload();
    }

    public LlmProviderDTO.DefaultProviderRequest getDefaultProvider() {
        String defaultChat = registry.resolveDefaultProviderId();
        String defaultEmb = registry.resolveDefaultEmbeddingProviderId();
        return new LlmProviderDTO.DefaultProviderRequest(defaultChat, defaultEmb);
    }

    @Transactional
    public void updateDefaultProvider(LlmProviderDTO.DefaultProviderRequest req) {
        if (req.defaultProvider() != null) {
            mapper.resetAllDefault();
            mapper.setDefault(req.defaultProvider());
            registry.reload();
        }
    }

    @Transactional
    public void updateDefaultEmbeddingProvider(LlmProviderDTO.DefaultProviderRequest req) {
        if (req.defaultEmbeddingProvider() != null) {
            mapper.resetAllDefaultEmbedding();
            mapper.setDefaultEmbedding(req.defaultEmbeddingProvider());
            registry.reload();
        }
    }

    public LlmProviderDTO.GatewayStatusVO getGatewayStatus() {
        String defaultProvider = registry.resolveDefaultProviderId();
        String defaultModel = "unknown";
        try {
            var cfg = registry.getDefaultProviderConfig();
            if (cfg != null && cfg.getModel() != null) {
                defaultModel = cfg.getModel();
            }
        } catch (Exception ignored) {}

        List<String> allConfigured = mapper.findAll().stream()
                .filter(e -> e.getApiKey() != null && !e.getApiKey().isBlank())
                .map(LlmProviderEntity::getId)
                .toList();

        List<String> failoverChain = registry.getOrderedFailoverList(defaultProvider);

        return LlmProviderDTO.GatewayStatusVO.builder()
                .defaultProvider(defaultProvider)
                .defaultModel(defaultModel)
                .availableProviders(allConfigured)
                .failoverChain(failoverChain)
                .globalPromptTokens(LlmCostMeterAdvisor.getGlobalPromptTokens())
                .globalCompletionTokens(LlmCostMeterAdvisor.getGlobalCompletionTokens())
                .globalTotalTokens(LlmCostMeterAdvisor.getGlobalTotalTokens())
                .globalTotalCostYuan(LlmCostMeterAdvisor.getGlobalTotalCostYuan())
                .isHealthy(!failoverChain.isEmpty())
                .build();
    }

    public LlmProviderDTO.GatewayTestResponse testGateway(LlmProviderDTO.GatewayTestRequest req) {
        String prompt = (req != null && req.prompt() != null && !req.prompt().isBlank())
                ? req.prompt().trim()
                : "请用一句话证明你的身份和模型名称。";

        String preferred = (req != null) ? req.preferredProvider() : null;
        long start = System.currentTimeMillis();

        try {
            String content = registry.executeWithFailover(preferred, client ->
                    client.prompt().user(prompt).call().content()
            );
            long costMs = System.currentTimeMillis() - start;

            return LlmProviderDTO.GatewayTestResponse.builder()
                    .success(true)
                    .targetProvider(preferred != null ? preferred : registry.resolveDefaultProviderId())
                    .actualProvider("Gateway-Verified")
                    .failoverOccurred(false)
                    .responseContent(content)
                    .durationMs(costMs)
                    .build();
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            log.warn("🚪 [网关测试] 调用失败: error={}", e.getMessage());
            return LlmProviderDTO.GatewayTestResponse.builder()
                    .success(false)
                    .targetProvider(preferred != null ? preferred : registry.resolveDefaultProviderId())
                    .actualProvider("None")
                    .failoverOccurred(true)
                    .durationMs(costMs)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private LlmProviderDTO.ProviderItem toItem(LlmProviderEntity e) {
        String maskedKey = (e.getApiKey() != null && e.getApiKey().length() > 8)
                ? e.getApiKey().substring(0, 4) + "****" + e.getApiKey().substring(e.getApiKey().length() - 4)
                : "****";
        boolean isDef = Boolean.TRUE.equals(e.getIsDefault());

        return LlmProviderDTO.ProviderItem.builder()
                .id(e.getId())
                .baseUrl(e.getBaseUrl())
                .maskedApiKey(maskedKey)
                .model(e.getModel())
                .embeddingModel(e.getEmbeddingModel())
                .embeddingDimensions(e.getEmbeddingDimensions())
                .supportsEmbedding(Boolean.TRUE.equals(e.getSupportsEmbedding()))
                .temperature(e.getTemperature())
                .defaultChatProvider(isDef)
                .defaultEmbeddingProvider(isDef && Boolean.TRUE.equals(e.getSupportsEmbedding()))
                .build();
    }
}