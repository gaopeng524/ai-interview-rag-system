package com.example.demo.modules.knowledgebase.model.dto;

import java.util.List;

/**
 * 知识库管理与 RAG 问答聚合 DTO
 */
public class KnowledgeBaseDTO {

    // ========== 请求 DTO ==========

    /**
     * 手动添加知识请求
     */
    public record AddRequest(
            String name,
            String content,
            String category
    ) {}

    /**
     * 知识库问答请求
     */
    public record QueryRequest(
            List<Long> knowledgeBaseIds,
            String question
    ) {}

    /**
     * 语义搜索请求
     */
    public record SearchRequest(
            String query,
            List<Long> knowledgeBaseIds,
            Integer topK
    ) {}

    // ========== 响应 DTO ==========

    /**
     * 文件上传并解析向量化响应
     */
    public record UploadResponse(
            Long id,
            String name,
            String category,
            Long fileSize,
            String message
    ) {}

    /**
     * 知识库问答响应
     */
    public record QueryResponse(
            String answer,
            Long knowledgeBaseId,
            String knowledgeBaseName
    ) {}

    /**
     * 语义检索结果项
     */
    public record SearchResultItem(
            String text,
            Double score,
            Long knowledgeBaseId
    ) {}
}
