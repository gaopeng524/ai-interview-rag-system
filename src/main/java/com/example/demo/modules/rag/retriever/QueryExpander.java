package com.example.demo.modules.rag.retriever;

import com.example.demo.common.ai.AiStructuredClient;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔍 检索前优化：Query 智能改写与 Multi-Query 意图扩展器
 * 
 * 核心原理：
 * 用户的原始提问往往存在口语化、包含复合多考点、或表述过于简略的问题。
 * 通过大模型将原问题分解为 2~3 个精准技术视角的衍生搜索词，多路并行召回，
 * 从根本上解决单一 Query 无法覆盖全部切片、导致召回率（Context Recall）断崖式下跌的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryExpander {

    private final AiStructuredClient aiStructuredClient;

    record QueryExpansionResponse(List<String> subQueries) {}

    /**
     * 对原始提问执行多视角子查询扩展
     */
    public List<String> expandQuery(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of();
        }

        // 短简单问题无需扩展，直接返回原词节省开销
        if (originalQuery.trim().length() < 12 && !originalQuery.contains("与") && !originalQuery.contains("和")) {
            return List.of(originalQuery.trim());
        }

        String prompt = String.format("""
                你是一个专业的技术搜索与 RAG 检索专家。请分析以下【用户提问】，将其拆解或扩展为 2~3 个专注于不同核心技术考点的独立检索关键词短语（适合在技术知识库向量库中检索）。
                
                【用户提问】：%s
                
                【输出绝对铁律】：
                1. 只能且仅能输出纯合法的 JSON 字符串，严禁输出任何思考过程、前言、问候或解释性大白话！
                2. 输出的第一个字符必须是 { ，最后一个字符必须是 } ！
                3. 严格遵循以下 JSON 结构：
                {
                  "subQueries": [
                    "子查询1",
                    "子查询2"
                  ]
                }
                """, originalQuery);

        try {
            QueryExpansionResponse resp = aiStructuredClient.callForStructured(
                    prompt,
                    new TypeReference<QueryExpansionResponse>() {},
                    () -> new QueryExpansionResponse(List.of(originalQuery))
            );

            if (resp != null && resp.subQueries() != null && !resp.subQueries().isEmpty()) {
                List<String> result = new ArrayList<>();
                result.add(originalQuery); // 始终保留原始问题
                for (String sq : resp.subQueries()) {
                    if (sq != null && !sq.isBlank() && !result.contains(sq.trim())) {
                        result.add(sq.trim());
                    }
                }
                log.info("🔍 [Query Multi-Expansion] 原提问='{}' ➔ 扩展为 {} 路检索: {}", originalQuery, result.size(), result);
                return result;
            }
        } catch (Exception e) {
            log.warn("⚠️ Query 扩展失败，降级使用原提问: error={}", e.getMessage());
        }

        return List.of(originalQuery);
    }
}