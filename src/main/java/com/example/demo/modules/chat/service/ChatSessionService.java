package com.example.demo.modules.chat.service;

import com.example.demo.modules.chat.model.dto.RagChatDTO;
import com.example.demo.modules.chat.model.entity.ChatMessage;
import com.example.demo.modules.chat.model.entity.ChatSession;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.chat.mapper.ChatMessageMapper;
import com.example.demo.modules.chat.mapper.ChatSessionMapper;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatMemoryService chatMemoryService;
    private final com.example.demo.common.ai.AiStructuredClient aiStructuredClient;

    /**
     * 创建新会话（支持关联知识库列表）
     */
    @Transactional
    public RagChatDTO.SessionResponse create(Long userId, RagChatDTO.CreateSessionRequest request) {
        List<Long> knowledgeBaseIds = request != null ? request.knowledgeBaseIds() : null;
        String title = request != null ? request.title() : null;
        return create(userId, knowledgeBaseIds, title);
    }

    /**
     * 创建新会话内部实现
     */
    @Transactional
    public RagChatDTO.SessionResponse create(Long userId, List<Long> knowledgeBaseIds, String title) {
        String sessionTitle = (title != null && !title.isBlank()) ? title.trim() : "新对话";

        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(sessionTitle);
        session.setIsPinned(false);
        chatSessionMapper.insert(session);

        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            chatSessionMapper.insertSessionKnowledgeBases(session.getId(), knowledgeBaseIds);
        }

        log.info("创建 RAG 聊天会话: id={}, title={}, kbIds={}", session.getId(), title, knowledgeBaseIds);

        return new RagChatDTO.SessionResponse(
                session.getId(),
                session.getTitle(),
                knowledgeBaseIds != null ? knowledgeBaseIds : List.of(),
                session.getCreatedAt() != null ? session.getCreatedAt().toString() : LocalDateTime.now().toString()
        );
    }

    public ChatSession findByIdAndUserId(Long id, Long userId) {
        return chatSessionMapper.findByIdAndUserId(id, userId);
    }

    /**
     * 获取会话列表（包含知识库名称、消息数、置顶状态等，对齐 inter 前端）
     */
    public List<RagChatDTO.SessionListItem> listSessions(Long userId) {
        List<ChatSession> sessions = chatSessionMapper.findByUserId(userId);
        String now = LocalDateTime.now().toString();

        return sessions.stream().map(session -> {
            int messageCount = chatSessionMapper.countMessagesBySessionId(session.getId());
            List<KnowledgeBase> kbs = chatSessionMapper.findKnowledgeBasesBySessionId(session.getId());
            List<String> kbNames = kbs.stream().map(KnowledgeBase::getName).toList();

            return new RagChatDTO.SessionListItem(
                    session.getId(),
                    session.getTitle(),
                    messageCount,
                    kbNames,
                    session.getUpdatedAt() != null ? session.getUpdatedAt().toString() :
                            (session.getCreatedAt() != null ? session.getCreatedAt().toString() : now),
                    Boolean.TRUE.equals(session.getIsPinned())
            );
        }).toList();
    }

    /**
     * 获取会话详情（包含消息历史与关联知识库）
     */
    public RagChatDTO.SessionDetail getSessionDetail(Long sessionId, Long userId) {
        ChatSession session = findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }

        List<KnowledgeBase> knowledgeBases = chatSessionMapper.findKnowledgeBasesBySessionId(sessionId);
        List<ChatMessage> messages = chatMessageMapper.findBySessionId(sessionId);

        String now = LocalDateTime.now().toString();
        List<RagChatDTO.MessageItem> messageList = messages.stream()
                .map(m -> new RagChatDTO.MessageItem(
                        m.getId(),
                        m.getRole(),  // user 或 assistant
                        m.getContent(),
                        m.getCreatedAt() != null ? m.getCreatedAt().toString() : now
                ))
                .toList();

        return new RagChatDTO.SessionDetail(
                session.getId(),
                session.getTitle(),
                knowledgeBases,
                messageList,
                session.getCreatedAt() != null ? session.getCreatedAt().toString() : now,
                session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : now
        );
    }

    /**
     * 获取指定会话关联的知识库 ID 列表
     */
    public List<Long> getKnowledgeBaseIds(Long sessionId) {
        return chatSessionMapper.findKnowledgeBaseIdsBySessionId(sessionId);
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateTitle(Long sessionId, Long userId, RagChatDTO.UpdateTitleRequest request) {
        String title = (request != null && request.title() != null && !request.title().isBlank())
                ? request.title().trim() : "新对话";
        ChatSession session = findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }
        chatSessionMapper.updateTitle(sessionId, title);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void togglePin(Long sessionId, Long userId) {
        ChatSession session = findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }
        chatSessionMapper.togglePin(sessionId);
    }

    /**
     * 更新会话关联的知识库
     */
    @Transactional
    public void updateKnowledgeBases(Long sessionId, Long userId, RagChatDTO.UpdateKnowledgeBasesRequest request) {
        List<Long> knowledgeBaseIds = request != null ? request.knowledgeBaseIds() : List.of();
        ChatSession session = findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }
        chatSessionMapper.deleteSessionKnowledgeBases(sessionId);
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            chatSessionMapper.insertSessionKnowledgeBases(sessionId, knowledgeBaseIds);
        }
        chatSessionMapper.updateUpdatedAt(sessionId);
        log.info("更新会话知识库关联: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 更新会话更新时间
     */
    public void updateUpdatedAt(Long sessionId) {
        chatSessionMapper.updateUpdatedAt(sessionId);
    }

    /**
     * 删除会话：删除消息 + 关联知识库 + 清理 Redis 短期记忆 + 会话本身
     */
    @Transactional
    public void delete(Long id, Long userId) {
        ChatSession session = findByIdAndUserId(id, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }

        chatMessageMapper.deleteBySessionId(id);
        chatSessionMapper.deleteSessionKnowledgeBases(id);
        chatSessionMapper.deleteById(id);
        chatMemoryService.clear(id);
        log.info("删除会话并清理缓存: sessionId={}", id);
    }

    /**
     * ChatGPT 同款：基于首句用户提问，大模型异步提炼 4~8 字高清智能会话标题（非阻塞）
     */
    public void asyncInferSmartTitle(Long sessionId, String userQuestion) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (userQuestion == null || userQuestion.isBlank()) return;

                String prompt = String.format("""
                        请根据用户的这段问题，提炼一个 4 到 8 个字以内的简短中文会话标题。
                        要求：
                        1. 只输出标题文本本身，严禁包含任何标点符号、引号、序号或多余解释。
                        2. 语言简练，精准概括核心技术或业务主题。
                        
                        用户提问：%s
                        """, userQuestion.trim());

                String smartTitle = null;
                if (aiStructuredClient != null) {
                    smartTitle = aiStructuredClient.callForStructured(
                            prompt,
                            new com.fasterxml.jackson.core.type.TypeReference<String>() {},
                            () -> fallbackTitle(userQuestion)
                    );
                } else {
                    smartTitle = fallbackTitle(userQuestion);
                }

                if (smartTitle != null && !smartTitle.isBlank()) {
                    smartTitle = smartTitle.replaceAll("[\\p{P}\\p{S}\\s]+", "");
                    if (smartTitle.length() > 10) {
                        smartTitle = smartTitle.substring(0, 10);
                    }
                    if (!smartTitle.isBlank()) {
                        chatSessionMapper.updateTitle(sessionId, smartTitle);
                        log.info("✨ [ChatGPT 首句智能命名] sessionId={}, 自动命名为: 《{}》", sessionId, smartTitle);
                    }
                }
            } catch (Exception e) {
                log.debug("异步提炼智能会话标题降级: {}", e.getMessage());
            }
        });
    }

    private String fallbackTitle(String text) {
        String clean = text.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return clean.length() > 8 ? clean.substring(0, 8) : clean;
    }
}
