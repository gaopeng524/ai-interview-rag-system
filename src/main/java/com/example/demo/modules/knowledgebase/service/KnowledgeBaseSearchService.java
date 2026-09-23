package com.example.demo.modules.knowledgebase.service;

import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.chat.service.ChatResilienceService;
import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.example.demo.common.ai.trace.TraceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索与 RAG 问答业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseSearchService {

    private final VectorStore vectorStore;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ChatResilienceService chatResilienceService;

    public List<Document> search(String query, int topK) {
        return search(query, Collections.emptyList(), topK, 0.3);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds) {
        return search(query, knowledgeBaseIds, 4, 0.3);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds, int topK) {
        return search(query, knowledgeBaseIds, topK, 0.3);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        int limit = topK > 0 ? topK : 4;
        double threshold = minScore > 0 ? minScore : 0.25;

        // 1. 若指定知识库，优先尝试 pgvector 数据库级前置过滤（双重匹配 kb_id 与 knowledge_base_id）
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            try {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                String idStr = String.valueOf(knowledgeBaseIds.get(0));

                SearchRequest.Builder builder = SearchRequest.builder()
                        .query(query)
                        .topK(limit)
                        .similarityThreshold(threshold);

                if (knowledgeBaseIds.size() == 1) {
                    builder.filterExpression(
                            b.or(b.eq("kb_id", idStr), b.eq("knowledge_base_id", idStr)).build()
                    );
                } else {
                    List<String> idStrs = knowledgeBaseIds.stream().map(String::valueOf).toList();
                    builder.filterExpression(
                            b.or(b.in("kb_id", idStrs.toArray()), b.in("knowledge_base_id", idStrs.toArray())).build()
                    );
                }

                List<Document> filteredResults = vectorStore.similaritySearch(builder.build());
                if (filteredResults != null && !filteredResults.isEmpty()) {
                    TraceManager.tag("rag.filterStrategy", "pgvector-prefilter");
                    TraceManager.tag("rag.hitCount", filteredResults.size());
                    return filteredResults;
                }
            } catch (Exception e) {
                log.warn("pgvector 前置元数据过滤异常，自动启用内存安全过滤: query={}, error={}", query, e.getMessage());
            }

            // 2. 稳妥降级：如果数据库前置过滤因底层类型差异未命中，做候选池宽检索后由内存过滤
            try {
                SearchRequest broadRequest = SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(limit * 5, 20))
                        .similarityThreshold(Math.max(0.15, threshold - 0.1))
                        .build();

                List<Document> candidates = vectorStore.similaritySearch(broadRequest);
                if (candidates != null && !candidates.isEmpty()) {
                    List<Document> matched = candidates.stream()
                            .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                            .limit(limit)
                            .collect(Collectors.toList());

                    if (!matched.isEmpty()) {
                        TraceManager.tag("rag.filterStrategy", "memory-fallback");
                        TraceManager.tag("rag.hitCount", matched.size());
                        return matched;
                    }
                }
            } catch (Exception e) {
                log.error("pgvector 内存降级检索失败: query={}, error={}", query, e.getMessage());
            }
            return Collections.emptyList();
        }

        // 3. 未指定知识库时，进行全局相似度检索
        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(threshold)
                    .build();
            return vectorStore.similaritySearch(req);
        } catch (Exception e) {
            log.warn("pgvector 全局相似度检索失败: query={}, error={}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        if (doc == null || doc.getMetadata() == null) return false;
        Object kbIdObj = doc.getMetadata().get("kb_id");
        if (kbIdObj == null) {
            kbIdObj = doc.getMetadata().get("knowledge_base_id");
        }
        if (kbIdObj == null) return false;
        try {
            Long id = Long.parseLong(kbIdObj.toString());
            return knowledgeBaseIds.contains(id);
        } catch (Exception e) {
            return false;
        }
    }

    public List<KnowledgeBaseDTO.SearchResultItem> searchSemantic(KnowledgeBaseDTO.SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return Collections.emptyList();
        }

        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 4;
        List<Document> docs = search(request.query(), request.knowledgeBaseIds(), topK, 0.25);
        List<KnowledgeBaseDTO.SearchResultItem> results = new ArrayList<>();

        for (Document doc : docs) {
            Object kbIdObj = doc.getMetadata().get("kb_id");
            if (kbIdObj == null) {
                kbIdObj = doc.getMetadata().get("knowledge_base_id");
            }
            Long kbId = kbIdObj instanceof Number ? ((Number) kbIdObj).longValue() :
                    (kbIdObj != null ? Long.parseLong(kbIdObj.toString()) : 0L);

            results.add(new KnowledgeBaseDTO.SearchResultItem(
                    doc.getText(),
                    doc.getScore() != null ? doc.getScore() : 0.0,
                    kbId
            ));
        }

        return results;
    }

    public KnowledgeBaseDTO.QueryResponse queryKnowledgeBase(KnowledgeBaseDTO.QueryRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return new KnowledgeBaseDTO.QueryResponse("提问内容不能为空", 0L, "");
        }
        List<Long> kbIds = request.knowledgeBaseIds();
        String question = request.question().trim();
        String answer = ask(question, kbIds);

        Long primaryKbId = 0L;
        String kbNames = "";
        if (kbIds != null && !kbIds.isEmpty()) {
            primaryKbId = kbIds.get(0);
            List<KnowledgeBase> kbs = knowledgeBaseMapper.findByIds(kbIds);
            kbNames = kbs.stream().map(KnowledgeBase::getName).collect(Collectors.joining("、"));
        }

        return new KnowledgeBaseDTO.QueryResponse(answer, primaryKbId, kbNames);
    }

    public Flux<String> queryKnowledgeBaseStream(KnowledgeBaseDTO.QueryRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Flux.just("提问内容不能为空");
        }
        List<Long> kbIds = request.knowledgeBaseIds();
        String question = request.question().trim();

        List<Document> relevantDocs = search(question, kbIds);
        if (relevantDocs.isEmpty()) {
            return Flux.just("抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。");
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = "你是一个知识库问答助手。请根据提供的知识库内容回答用户问题。" +
                "如果知识库中没有相关信息，请明确说明。回答要准确、简洁。";

        String userPrompt = String.format("""
                %s
                
                知识库内容：
                %s
                
                用户问题：%s
                
                请基于知识库内容回答：
                """, systemPrompt, context, question);

        String convId = "kb-query-" + (kbIds != null && !kbIds.isEmpty() ? kbIds.get(0) : "all");
        return chatResilienceService.streamChat(convId, userPrompt, List.of());
    }

    public String ask(String question, List<Long> knowledgeBaseIds) {
        log.info("RAG 问答: question={}, kbIds={}", question, knowledgeBaseIds);
        List<Document> relevantDocs = search(question, knowledgeBaseIds);

        if (relevantDocs.isEmpty()) {
            return "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = "你是一个知识库问答助手。请根据提供的知识库内容回答用户问题。" +
                "如果知识库中没有相关信息，请明确说明。回答要准确、简洁。";

        String userPrompt = String.format("""
                请根据以下知识库内容回答问题。如果知识库中没有相关信息，请明确说明。
                
                知识库内容：
                %s
                
                用户问题：%s
                
                请基于知识库内容回答：
                """, context, question);

        return llmProviderRegistry.executeWithFailover(null, client ->
                client.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content()
        );
    }

    @CacheEvict(value = "rag_search_cache", allEntries = true)
    public void evictRagCache() {
        log.info("[知识库检索] 已成功清空全部 RAG 检索缓存！");
    }

    @Cacheable(value = "rag_search_cache", key = "T(com.example.demo.common.util.HashUtils).md5(#question + '_' + #knowledgeBaseIds)", unless = "#result == null || #result.isEmpty()")
    public List<Document> searchCached(String question, List<Long> knowledgeBaseIds) {
        log.info("🔍 [知识库缓存检索] 未命中 Redis 缓存，正在执行 pgvector 相似度检索: query={}, kbIds={}", question, knowledgeBaseIds);
        return search(question, knowledgeBaseIds);
    }
}