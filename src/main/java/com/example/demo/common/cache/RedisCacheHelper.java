package com.example.demo.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 通用泛型 Redis 缓存操作助手（封装 Cache-Aside 模式与模式失效）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Cache-Aside 模式：优先读取 Redis 缓存；若未命中则通过 loader 加载并回填 Redis
     */
    public <T> T getOrLoad(String cacheKey, Duration ttl, TypeReference<T> typeRef, Supplier<T> loader) {
        // 1. 读取缓存
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                T data = objectMapper.readValue(cachedJson, typeRef);
                if (data != null) {
                    log.info("⚡ [Redis 缓存命中] key={}", cacheKey);
                    return data;
                }
            }
        } catch (Exception e) {
            log.warn("读取 Redis 缓存异常: key={}, error={}", cacheKey, e.getMessage());
        }

        // 2. 缓存未命中：执行底层数据加载
        T result = loader.get();

        // 3. 回填缓存
        if (result != null) {
            try {
                String json = objectMapper.writeValueAsString(result);
                redisTemplate.opsForValue().set(cacheKey, json, ttl);
                log.info("💾 [Redis 缓存写入] key={}, ttl={}", cacheKey, ttl);
            } catch (Exception e) {
                log.warn("写入 Redis 缓存异常: key={}, error={}", cacheKey, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 根据通配符模式批量删除缓存 Key（如 "cache:rag:search:*"）
     */
    public void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("🧹 [Redis 缓存清理] 匹配模式 '{}'，已清理 {} 个 Key", pattern, keys.size());
            }
        } catch (Exception e) {
            log.warn("清理 Redis 缓存异常: pattern={}, error={}", pattern, e.getMessage());
        }
    }
}
