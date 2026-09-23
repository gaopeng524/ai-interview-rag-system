package com.example.demo.modules.rag.engine;

import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.example.demo.modules.rag.assembler.RagContextAssembler;
import com.example.demo.modules.rag.model.RagDocument;
import com.example.demo.modules.rag.model.RagSearchRequest;
import com.example.demo.modules.rag.model.RagSearchResult;
import com.example.demo.modules.rag.rerank.RagReranker;
import com.example.demo.modules.rag.retriever.RagRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产级 Modular RAG 执行引擎中枢
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEngine {

    private final RagRetriever ragRetriever;
    private final RagReranker ragReranker;
    private final RagContextAssembler contextAssembler;
    private final LlmProviderRegistry llmProviderRegistry;

    /**
     * 执行完整的 RAG 检索流水线：意图扩展 ➔ 候选召回 ➔ 语义精排/U型重排 ➔ 父子块级联展开 ➔ 上下文组装
     */
    public RagSearchResult retrieve(RagSearchRequest request) {
        long start = System.currentTimeMillis();
        String query = request != null ? request.getQuery() : "";

        // 1. 意图多路扩展 + 候选文档召回
        List<RagDocument> candidates = ragRetriever.retrieveCandidates(request);

        if (candidates.isEmpty()) {
            return RagSearchResult.builder()
                    .originalQuery(query)
                    .documents(List.of())
                    .assembledContext("")
                    .traceInfo(Map.of("costMs", System.currentTimeMillis() - start, "candidateCount", 0))
                    .build();
        }

        // 2. 执行 Rerank 精排与 U 型重排 (若开启)
        List<RagDocument> finalDocs;
        if (Boolean.TRUE.equals(request.getEnableRerank())) {
            finalDocs = ragReranker.rerank(
                    query,
                    candidates,
                    request.getTopK(),
                    Boolean.TRUE.equals(request.getEnableUShapedRerank())
            );
        } else {
            finalDocs = candidates.stream().limit(request.getTopK()).collect(Collectors.toList());
        }

        // 🌟 3. 父子切片级联展开 (若切片带有 parent_content，自动按 parent_id 去重展开为完整父上下文)
        List<RagDocument> expandedDocs = expandToParentContext(finalDocs);

        // 4. 组装增强上下文文本
        String assembledContext = contextAssembler.assembleContextText(expandedDocs);

        long cost = System.currentTimeMillis() - start;
        Map<String, Object> traceInfo = new HashMap<>();
        traceInfo.put("costMs", cost);
        traceInfo.put("candidateCount", candidates.size());
        traceInfo.put("finalCount", finalDocs.size());
        traceInfo.put("expandedCount", expandedDocs.size());

        log.info("🚀 [RagEngine 检索完成] query='{}', 候选数={}, 最终入选数={}, 父块展开数={}, 耗时={}ms",
                query, candidates.size(), finalDocs.size(), expandedDocs.size(), cost);

        return RagSearchResult.builder()
                .originalQuery(query)
                .documents(expandedDocs)
                .assembledContext(assembledContext)
                .traceInfo(traceInfo)
                .build();
    }

    private List<RagDocument> expandToParentContext(List<RagDocument> docs) {
        if (docs == null || docs.isEmpty()) return List.of();

        Map<String, RagDocument> uniqueParents = new LinkedHashMap<>();
        List<RagDocument> result = new ArrayList<>();

        for (RagDocument doc : docs) {
            if (doc.getMetadata() != null && doc.getMetadata().containsKey("parent_id") && doc.getMetadata().containsKey("parent_content")) {
                String parentId = String.valueOf(doc.getMetadata().get("parent_id"));
                String parentContent = String.valueOf(doc.getMetadata().get("parent_content"));

                if (!uniqueParents.containsKey(parentId)) {
                    RagDocument parentDoc = RagDocument.builder()
                            .id(parentId)
                            .text(parentContent)
                            .vectorScore(doc.getVectorScore())
                            .rerankScore(doc.getRerankScore())
                            .finalScore(doc.getFinalScore())
                            .metadata(doc.getMetadata())
                            .build();
                    uniqueParents.put(parentId, parentDoc);
                    result.add(parentDoc);
                }
            } else {
                result.add(doc);
            }
        }
        return result;
    }

    public List<Document> retrieveAsSpringAiDocuments(RagSearchRequest request) {
        RagSearchResult result = retrieve(request);
        return result.getDocuments().stream()
                .map(RagDocument::toSpringAiDocument)
                .collect(Collectors.toList());
    }

    public List<Document> retrieveAsSpringAiDocuments(String query, List<Long> kbIds, int topK) {
        RagSearchRequest request = RagSearchRequest.builder()
                .query(query)
                .knowledgeBaseIds(kbIds)
                .topK(topK)
                .build();

        RagSearchResult result = retrieve(request);
        return result.getDocuments().stream()
                .map(RagDocument::toSpringAiDocument)
                .collect(Collectors.toList());
    }

    /**
     * 阻塞式生成问答（🌟 接入大模型网关多模型主备故障转移）
     */
    public String generateAnswer(RagSearchRequest request) {
        RagSearchResult searchResult = retrieve(request);
        String systemPrompt = contextAssembler.assembleSystemPrompt(searchResult.getDocuments());

        return llmProviderRegistry.executeWithFailover(request.getLlmProvider(), client ->
                client.prompt()
                        .system(systemPrompt)
                        .user(request.getQuery())
                        .call()
                        .content()
        );
    }

    /**
     * 响应式 SSE 流式生成问答（🌟 接入大模型网关流式故障转移）
     */
    public Flux<String> chatStream(RagSearchRequest request) {
        RagSearchResult searchResult = retrieve(request);
        String systemPrompt = contextAssembler.assembleSystemPrompt(searchResult.getDocuments());

        return llmProviderRegistry.executeStreamWithFailover(request.getLlmProvider(), client ->
                client.prompt()
                        .system(systemPrompt)
                        .user(request.getQuery())
                        .stream()
                        .content()
        );
    }
}