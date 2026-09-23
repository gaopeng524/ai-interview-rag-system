package com.example.demo.modules.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一 RAG 检索输出结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResult {

    /** 原始用户提问 */
    private String originalQuery;

    /** 扩展出的子查询列表 (若开启了 Multi-Query) */
    private List<String> expandedQueries;

    /** 最终筛选精排后的切片列表 */
    private List<RagDocument> documents;

    /** 智能组装好的结构化上下文文本 (可直接注入 Prompt) */
    private String assembledContext;

    /** 检索全链路耗时与诊断调试信息 */
    private Map<String, Object> traceInfo;
}