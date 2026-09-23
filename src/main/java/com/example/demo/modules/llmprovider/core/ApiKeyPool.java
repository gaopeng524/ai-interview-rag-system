package com.example.demo.modules.llmprovider.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🔑 大模型网关·多 API Key 轮询与负载均衡池 (API Key Load Balancing Pool)
 */
@Slf4j
@Component
public class ApiKeyPool {

    private final Map<String, AtomicInteger> providerIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Long> keyCooldownMap = new ConcurrentHashMap<>();

    private static final long COOLDOWN_DURATION_MS = 60_000L; // 冷却 60 秒

    /**
     * 从原始 Key 配置字符串中获取当前可用的 Key（支持单 Key 与多 Key 轮询）
     */
    public String acquireKey(String providerId, String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return "";
        }

        if (!rawApiKey.contains(",")) {
            return rawApiKey.trim();
        }

        List<String> keyList = Arrays.stream(rawApiKey.split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .toList();

        if (keyList.isEmpty()) {
            return rawApiKey.trim();
        }

        if (keyList.size() == 1) {
            return keyList.get(0);
        }

        AtomicInteger counter = providerIndexMap.computeIfAbsent(providerId, k -> new AtomicInteger(0));
        long now = System.currentTimeMillis();

        for (int i = 0; i < keyList.size(); i++) {
            int idx = Math.abs(counter.getAndIncrement() % keyList.size());
            String candidateKey = keyList.get(idx);

            Long cooldownUntil = keyCooldownMap.get(candidateKey);
            if (cooldownUntil == null || now > cooldownUntil) {
                if (cooldownUntil != null) {
                    keyCooldownMap.remove(candidateKey);
                    log.info("🔑 [网关·Key池] Key【{}...】冷却期已过，恢复健康状态！", maskKey(candidateKey));
                }
                return candidateKey;
            }
        }

        int fallbackIdx = Math.abs(counter.getAndIncrement() % keyList.size());
        return keyList.get(fallbackIdx);
    }

    /**
     * 当某个 Key 遭遇 429 限流或报错时，将其加入临时冷却黑名单
     */
    public void markKeyCooldown(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            keyCooldownMap.put(apiKey.trim(), System.currentTimeMillis() + COOLDOWN_DURATION_MS);
            log.warn("⚠️ [网关·Key池] Key【{}...】触发限流或异常，已临时冷冻 60 秒！", maskKey(apiKey));
        }
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
    }
}