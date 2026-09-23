package com.example.demo.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Mem0 官方开源智能记忆中枢微服务 Java 客户端
 * 基于 Spring 6 RestClient，支持长短期事实提取、跨会话自适应画像检索与 Fail-Open 故障安全降级。
 */
@Slf4j
@Component
public class Mem0Client {

    private final RestClient restClient;
    private final boolean enabled;

    public Mem0Client(
            RestClient.Builder builder,
            @Value("${app.mem0.base-url:http://localhost:8888/v1}") String baseUrl,
            @Value("${app.mem0.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "http://localhost:8888/v1";
        this.restClient = builder.baseUrl(effectiveBaseUrl).build();
        log.info("Mem0Client initialized: baseUrl={}, enabled={}", effectiveBaseUrl, enabled);
    }

    /**
     * 异步存入记忆（自动由 Mem0 提炼事实、实体链接并存入向量库）
     */
    public boolean addMemory(String content, Long userId) {
        if (!enabled || content == null || content.isBlank() || userId == null) {
            return false;
        }

        try {
            Map<String, Object> body = Map.of(
                    "text", content,
                    "user_id", "user_" + userId
            );

            restClient.post()
                    .uri("/memories/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("💾 [Mem0 记忆沉淀] userId={}, 文本长度={}", userId, content.length());
            return true;
        } catch (Exception e) {
            // Fail-Open 保护：如果 Mem0 容器不可达，记录日志但绝不中断用户业务主流程
            log.warn("💾 [Mem0 降级] 存入记忆失败: userId={}, error={}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 语义检索与当前话题相关的历史记忆事实
     */
    public List<String> searchMemory(String query, Long userId, int limit) {
        if (!enabled || query == null || query.isBlank() || userId == null) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> body = Map.of(
                    "query", query,
                    "user_id", "user_" + userId,
                    "limit", limit > 0 ? limit : 3
            );

            List<String> results = restClient.post()
                    .uri("/memories/search/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {});

            if (results != null && !results.isEmpty()) {
                log.info("⚡ [Mem0 记忆命中] userId={}, 召回 {} 条事实", userId, results.size());
                return results;
            }
        } catch (Exception e) {
            log.warn("⚡ [Mem0 降级] 检索记忆失败: userId={}, error={}", userId, e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * 便捷方法：生成直接可注入 System Prompt 的精炼画像片段
     */
    public String buildMemoryPrompt(Long userId, String query) {
        List<String> memories = searchMemory(query, userId, 3);
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n【考生历史长期技术画像与薄弱点（请参考因材施教）】:\n");
        for (String m : memories) {
            sb.append("• ").append(m).append("\n");
        }
        return sb.toString();
    }
}