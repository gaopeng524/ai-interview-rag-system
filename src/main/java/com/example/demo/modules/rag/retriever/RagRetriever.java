package com.example.demo.modules.rag.retriever;

import com.example.demo.common.cache.RedisCacheHelper;
import com.example.demo.modules.rag.model.RagDocument;
import com.example.demo.modules.rag.model.RagSearchRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 🔀 检索层：自适应多路向量召回与候选集初筛
 * 
 * 核心优化：
 * 1. 扩增初筛候选池（Candidate Pool）：初筛取 TopK * candidateMultiplier（如 8~12 条），杜绝过早截断；
 * 2. 多路 Query 并行去重融合：合并 Multi-Query 搜出的多批次切片，相同切片保留最高相似度分；
 * 3. 租户隔离元数据过滤 (metadata.kb_id in [...])；
 * 4. Redis 毫秒级两级缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetriever {

    private final VectorStore vectorStore;
    private final RedisCacheHelper redisCacheHelper;
    private final QueryExpander queryExpander;

    private static final Duration RAG_CACHE_TTL = Duration.ofHours(24);
    private static final String RAG_CACHE_PREFIX = "cache:rag:vector:";

    /**
     * 执行多路候选召回与去重
     */
    public List<RagDocument> retrieveCandidates(RagSearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return List.of();
        }

        // 1. 是否执行 Multi-Query 改写扩展
        List<String> searchQueries;
        if (Boolean.TRUE.equals(request.getEnableQueryExpansion())) {
            searchQueries = queryExpander.expandQuery(request.getQuery());
        } else {
            searchQueries = List.of(request.getQuery());
        }

        // 2. 候选池大小（例如 TopK=4, multiplier=3 ➔ 候选池拉取 12 条）
        int candidateTopK = Math.max(request.getTopK() * request.getCandidateMultiplier(), 8);

        Map<String, RagDocument> uniqueCandidates = new LinkedHashMap<>();

        for (String q : searchQueries) {
            List<RagDocument> docsForQ = searchSingleQuery(q, request.getKnowledgeBaseIds(), candidateTopK, request.getMinScore());
            for (RagDocument doc : docsForQ) {
                // 去重合并：同一切片保留最高向量分
                if (!uniqueCandidates.containsKey(doc.getId())) {
                    uniqueCandidates.put(doc.getId(), doc);
                } else {
                    RagDocument exist = uniqueCandidates.get(doc.getId());
                    if (doc.getVectorScore() != null && (exist.getVectorScore() == null || doc.getVectorScore() > exist.getVectorScore())) {
                        exist.setVectorScore(doc.getVectorScore());
                        exist.setFinalScore(doc.getVectorScore());
                    }
                }
            }
        }

        List<RagDocument> sortedCandidates = new ArrayList<>(uniqueCandidates.values());
        sortedCandidates.sort((a, b) -> Double.compare(
                b.getVectorScore() != null ? b.getVectorScore() : 0.0,
                a.getVectorScore() != null ? a.getVectorScore() : 0.0
        ));

        log.info("📥 [RagRetriever 候选召回] 汇总多路检索去重后候选切片数: {} (目标 TopK={})", sortedCandidates.size(), request.getTopK());
        return sortedCandidates;
    }

    /**
     * 单个 Query 的向量检索（带 Redis 缓存保护与稳妥过滤）
     */
    private List<RagDocument> searchSingleQuery(String query, List<Long> kbIds, int topK, double minScore) {
        String cacheKey = buildCacheKey(query, kbIds, topK, minScore);

        return redisCacheHelper.getOrLoad(
                cacheKey,
                RAG_CACHE_TTL,
                new TypeReference<List<RagDocument>>() {},
                () -> {
                    try {
                        List<Document> docs = doVectorSearch(query, kbIds, topK, minScore);
                        if (docs == null || docs.isEmpty()) {
                            return List.of();
                        }

                        return docs.stream()
                                .map(RagDocument::fromSpringAiDocument)
                                .collect(Collectors.toList());
                    } catch (Exception e) {
                        log.error("❌ 向量检索异常: query={}, error={}", query, e.getMessage(), e);
                        return List.of();
                    }
                }
        );
    }

    private List<Document> doVectorSearch(String query, List<Long> kbIds, int topK, double minScore) {
        if (kbIds == null || kbIds.isEmpty()) {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(minScore)
                    .build();
            return vectorStore.similaritySearch(request);
        }

        // 1. 尝试数据库前置过滤
        try {
            String filterExpr = buildKbFilterExpression(kbIds);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(minScore)
                    .filterExpression(filterExpr)
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            if (results != null && !results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("向量前置过滤检索异常，尝试回退内存过滤: {}", e.getMessage());
        }

        // 2. 稳妥回退：检索候选池后在内存中按 metadata 的 kb_id 过滤
        try {
            SearchRequest fallbackRequest = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(topK * 4, 20))
                    .similarityThreshold(minScore)
                    .build();
            List<Document> allResults = vectorStore.similaritySearch(fallbackRequest);
            if (allResults == null || allResults.isEmpty()) return List.of();

            return allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, kbIds))
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("向量搜索完全失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        if (doc == null || doc.getMetadata() == null) return false;
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) return false;
        try {
            Long id = Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(id);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    private String buildCacheKey(String query, List<Long> kbIds, int topK, double minScore) {
        String raw = (query != null ? query.trim() : "")
                + "|" + (kbIds != null ? kbIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")) : "ALL")
                + "|" + topK
                + "|" + minScore;
        return RAG_CACHE_PREFIX + sha256Hex(raw);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}