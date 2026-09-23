package com.example.demo.modules.chat.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.modules.chat.mapper.ChatMessageMapper;
import com.example.demo.modules.chat.model.entity.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 滚动摘要 + 短期缓冲记忆服务（ConversationSummaryBufferMemory）
 * 维护 Redis 双层结构：
 * 1. chat:summary:{sessionId} -> 存全局滚动前情提要（50~150字精炼摘要）
 * 2. chat:memory:{sessionId}  -> 存最近 4 条原生消息（2 问 2 答滑动窗口）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private final StringRedisTemplate redisTemplate;
    private final ChatMessageMapper chatMessageMapper;
    private final com.example.demo.modules.chat.mapper.ChatSessionMapper chatSessionMapper;
    private final AiStructuredClient aiStructuredClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MEMORY_KEY_PREFIX = "chat:memory:";
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final Duration MEMORY_TTL = Duration.ofHours(24);
    private static final int MAX_HISTORY_MESSAGES = 4;

    public record MemoryMessage(String role, String content) {}

    /**
     * 获取会话的全局前情摘要
     */
    public String getSummary(Long sessionId) {
        if (sessionId == null) return "";
        try {
            String summary = redisTemplate.opsForValue().get(SUMMARY_KEY_PREFIX + sessionId);
            return summary != null ? summary.trim() : "";
        } catch (Exception e) {
            log.debug("读取 Redis 会话摘要失败: sessionId={}, error={}", sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 更新会话的全局前情摘要
     */
    public void updateSummary(Long sessionId, String summary) {
        if (sessionId == null || summary == null || summary.isBlank()) return;
        try {
            redisTemplate.opsForValue().set(SUMMARY_KEY_PREFIX + sessionId, summary.trim(), MEMORY_TTL);
        } catch (Exception e) {
            log.debug("更新 Redis 会话摘要失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 获取最近对话历史消息（优先查 Redis，未命中则回源 DB）
     */
    public List<Message> getHistory(Long sessionId) {
        String key = MEMORY_KEY_PREFIX + sessionId;

        try {
            List<String> rawList = redisTemplate.opsForList().range(key, 0, -1);
            if (rawList != null && !rawList.isEmpty()) {
                List<Message> messages = new ArrayList<>();
                for (String json : rawList) {
                    MemoryMessage mm = objectMapper.readValue(json, MemoryMessage.class);
                    if ("user".equalsIgnoreCase(mm.role())) {
                        messages.add(new UserMessage(mm.content()));
                    } else if ("assistant".equalsIgnoreCase(mm.role())) {
                        messages.add(new AssistantMessage(mm.content()));
                    }
                }
                return messages;
            }
        } catch (Exception e) {
            log.warn("读取 Redis 会话记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }

        // 回源数据库并预热 Redis
        List<ChatMessage> dbMessages = chatMessageMapper.findBySessionId(sessionId);
        if (dbMessages == null || dbMessages.isEmpty()) {
            return Collections.emptyList();
        }

        // 取最近的 N 条
        int fromIndex = Math.max(0, dbMessages.size() - MAX_HISTORY_MESSAGES);
        List<ChatMessage> recent = dbMessages.subList(fromIndex, dbMessages.size());

        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : recent) {
            if ("user".equalsIgnoreCase(msg.getRole())) {
                result.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                result.add(new AssistantMessage(msg.getContent()));
            }
            append(sessionId, msg.getRole(), msg.getContent());
        }

        return result;
    }

    /**
     * 向会话记忆追加一条新消息并保持滑动窗口大小
     */
    public void append(Long sessionId, String role, String content) {
        String key = MEMORY_KEY_PREFIX + sessionId;
        try {
            MemoryMessage mm = new MemoryMessage(role, content);
            String json = objectMapper.writeValueAsString(mm);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -MAX_HISTORY_MESSAGES, -1);
            redisTemplate.expire(key, MEMORY_TTL);
        } catch (Exception e) {
            log.warn("追加 Redis 会话记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 异步增量滚动压缩前情提要（ConversationSummaryBufferMemory 核心引擎）
     * 在后台静默运行，绝不阻塞前端用户打字响应
     */
    public void asyncCheckAndCompressSummary(Long sessionId, String userMsg, String assistantMsg) {
        if (sessionId == null || userMsg == null || assistantMsg == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                // 检查该会话累计的消息总数
                int totalCount = (chatSessionMapper != null) ? chatSessionMapper.countMessagesBySessionId(sessionId) : 0;
                // 只有当消息数超过滑动窗口 (>= 6 条，即至少 3 轮问答) 时才开始滚动压缩摘要
                if (totalCount < 6) return;

                String currentSummary = getSummary(sessionId);

                String prompt = String.format("""
                        请将以下【新产生的一轮对话】增量合并到【现有的前情摘要】中，提炼出一段更完善但依然精练的会话前情提要。
                        
                        要求：
                        1. 重点保留用户提到的开发环境（如JDK版本、框架）、核心技术需求、关键业务约束与重要结论。
                        2. 忽略寒暄与无意义客套，字数严格控制在 80~150 字以内。
                        3. 严禁任何多余的开场白或解释，直接输出合并后的纯摘要内容。
                        
                        【现有的前情摘要】：%s
                        
                        【新产生的一轮对话】：
                        User: %s
                        Assistant: %s
                        """, currentSummary.isBlank() ? "暂无" : currentSummary, userMsg.trim(), assistantMsg.trim());

                String newSummary = null;
                if (aiStructuredClient != null) {
                    newSummary = aiStructuredClient.callForStructured(
                            prompt,
                            new TypeReference<String>() {},
                            () -> currentSummary
                    );
                }

                if (newSummary != null && !newSummary.isBlank() && !newSummary.equals(currentSummary)) {
                    updateSummary(sessionId, newSummary);
                    log.info("📝 [滚动摘要增量更新] sessionId={}, 新摘要字数={}, 内容: {}", sessionId, newSummary.length(), newSummary);
                }
            } catch (Exception e) {
                log.debug("异步滚动压缩会话摘要跳过: sessionId={}, error={}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 清理会话所有短期记忆与滚动摘要
     */
    public void clear(Long sessionId) {
        try {
            redisTemplate.delete(List.of(MEMORY_KEY_PREFIX + sessionId, SUMMARY_KEY_PREFIX + sessionId));
        } catch (Exception e) {
            log.warn("清理 Redis 会话记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
