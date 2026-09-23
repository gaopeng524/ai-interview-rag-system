package com.example.demo.modules.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一 RAG 检索与生成请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchRequest {

    /** 用户提问 / 检索 Query */
    private String query;

    /** 目标知识库 ID 列表 (为空则全局检索) */
    private List<Long> knowledgeBaseIds;

    /** 期望最终返回的切片数量 (默认 4) */
    @Builder.Default
    private Integer topK = 4;

    /** 候选池扩增倍数 (用于 Rerank 精排前初筛，默认 2.5 倍) */
    @Builder.Default
    private Integer candidateMultiplier = 3;

    /** 最小向量相似度阈值 (默认 0.25) */
    @Builder.Default
    private Double minScore = 0.25;

    /** 是否启用 Multi-Query 意图改写与扩展 (默认 false，长复杂问题自动或手动开启) */
    @Builder.Default
    private Boolean enableQueryExpansion = false;

    /** 是否启用 Reranker 二次精排 (默认 true) */
    @Builder.Default
    private Boolean enableRerank = true;

    /** 是否启用 U 型重排 (Lost-in-the-Middle 规避策略，默认 true) */
    @Builder.Default
    private Boolean enableUShapedRerank = true;

    /** 指定使用的 LLM 供应商 */
    private String llmProvider;
}