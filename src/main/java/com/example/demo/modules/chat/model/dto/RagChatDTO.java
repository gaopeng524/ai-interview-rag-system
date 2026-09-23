package com.example.demo.modules.chat.model.dto;

import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;

import java.util.List;

/**
 * 会话与 RAG 聊天聚合 DTO
 */
public class RagChatDTO {

    // ========== 请求 DTO ==========

    /**
     * 创建会话请求
     */
    public record CreateSessionRequest(
            List<Long> knowledgeBaseIds,
            String title
    ) {}

    /**
     * 发送流式消息请求（兼容 question 与 content）
     */
    public record SendMessageRequest(
            String question,
            String content,
            String knowledgeBaseId
    ) {
        public String getMessageContent() {
            return (question != null && !question.isBlank()) ? question : content;
        }
    }

    /**
     * 更新会话标题请求
     */
    public record UpdateTitleRequest(
            String title
    ) {}

    /**
     * 更新会话关联知识库请求
     */
    public record UpdateKnowledgeBasesRequest(
            List<Long> knowledgeBaseIds
    ) {}

    // ========== 响应 DTO ==========

    /**
     * 创建会话响应
     */
    public record SessionResponse(
            Long id,
            String title,
            List<Long> knowledgeBaseIds,
            String createdAt
    ) {}

    /**
     * 会话列表项（侧边栏展示）
     */
    public record SessionListItem(
            Long id,
            String title,
            Integer messageCount,
            List<String> knowledgeBaseNames,
            String updatedAt,
            Boolean isPinned
    ) {}

    /**
     * 会话详情（包含消息历史与关联知识库）
     */
    public record SessionDetail(
            Long id,
            String title,
            List<KnowledgeBase> knowledgeBases,
            List<MessageItem> messages,
            String createdAt,
            String updatedAt
    ) {}

    /**
     * 消息项
     */
    public record MessageItem(
            Long id,
            String type,
            String content,
            String createdAt
    ) {}
}
