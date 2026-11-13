package com.example.demo.modules.llmprovider.core;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Map;
import java.util.Set;

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

    /** 额外可信的 HTTPS 模型供应商主机白名单 */
    private Set<String> additionalAllowedHosts = Set.of();

    public URI requireAllowedEndpoint(String baseUrl) {
        URI endpoint = parseEndpoint(baseUrl);
        if (endpoint != null) {
            String host = endpoint.getHost();
            boolean configured = providers != null && providers.values().stream()
                    .map(ProviderConfig::getBaseUrl)
                    .map(LlmProviderProperties::parseEndpoint)
                    .anyMatch(uri -> uri != null && host.equalsIgnoreCase(uri.getHost()));
            if (configured || additionalAllowedHosts.stream().anyMatch(host::equalsIgnoreCase)) {
                return endpoint;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "模型地址必须使用已配置的可信 HTTPS 主机");
    }

    private static URI parseEndpoint(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid or ambiguous URL.
        }
        return null;
    }

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