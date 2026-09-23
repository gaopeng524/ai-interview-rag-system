package com.example.demo.modules.rag.rerank;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.modules.rag.model.RagDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 🎯 检索后精排：Reranker 交叉评分与 U 型重排 (Lost-in-the-Middle 规避)
 * 
 * 核心优化：
 * 1. 语义交叉打分 (Cross-Encoder / LLM Scoring)：从初筛的 8~12 条候选切片中，重新评估每一篇切片对回答问题的真实贡献度 (0.0~1.0)，强力剔除伪相似的噪声切片（解决 Context Precision 低下的痛点）；
 * 2. 动态自适应截断 (Dynamic Cutoff)：只保留高分切片，过滤纯噪声；
 * 3. U 型重排 (U-Shaped Reordering / Lost-in-the-Middle 规避)：
 *    根据大语言模型注意力机制，Prompt 开头与结尾的信息记忆最深，中间极易被遗忘。
 *    将 Rank 1（最核心切片）放在最前，Rank 2 放在最后，次优切片置于中间，实现注意力最大化！
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagReranker {

    private final AiStructuredClient aiStructuredClient;

    record RerankScoreItem(int index, double relevanceScore, String reason) {}
    record RerankResponse(List<RerankScoreItem> items) {}

    /**
     * 对候选切片执行交叉精排与 U 型重排
     */
    public List<RagDocument> rerank(String query, List<RagDocument> candidates, int targetTopK, boolean enableUShaped) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 如果候选切片数量本来就 <= targetTopK，直接按向量分排序后进行 U 型重排
        if (candidates.size() <= 2) {
            return candidates;
        }

        // 1. 组装精排 Prompt，让大模型充当 Cross-Encoder 评分器
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String preview = candidates.get(i).getText().replaceAll("\\s+", " ");
            sb.append(String.format("【切片编号 %d】: %s\n\n", i, preview));
        }

        String prompt = String.format("""
                你是一个高精度的技术文档检索重排器 (Reranker)。请仔细分析【用户提问】与下列【候选切片】，评估每个切片对准确回答该问题的直接价值与语义相关度。
                请对每个切片给出 0.0 ~ 1.0 之间的相关性得分 relevanceScore：
                - 0.8 ~ 1.0: 包含该问题核心考点的直接答案或关键事实依据；
                - 0.4 ~ 0.7: 属于同技术领域但非直接答案的背景信息；
                - 0.0 ~ 0.3: 完全不相干的跨主题噪声。
                
                【用户提问】：%s
                
                【候选切片】：
                %s
                
                请直接输出 JSON 格式：
                {
                  "items": [
                    {"index": 0, "relevanceScore": 0.95, "reason": "直接包含核心答案"},
                    ...
                  ]
                }
                """, query, sb.toString());

        try {
            RerankResponse resp = aiStructuredClient.callForStructured(
                    prompt,
                    new TypeReference<RerankResponse>() {},
                    () -> new RerankResponse(List.of())
            );

            if (resp != null && resp.items() != null && !resp.items().isEmpty()) {
                Map<Integer, Double> scoreMap = resp.items().stream()
                        .collect(Collectors.toMap(
                                RerankScoreItem::index,
                                RerankScoreItem::relevanceScore,
                                (a, b) -> Math.max(a, b)
                        ));

                for (int i = 0; i < candidates.size(); i++) {
                    RagDocument doc = candidates.get(i);
                    Double rScore = scoreMap.getOrDefault(i, 0.5);
                    doc.setRerankScore(rScore);
                    // 综合评分 = 0.7 * 重排语义分 + 0.3 * 原始向量相似度
                    double vecScore = doc.getVectorScore() != null ? doc.getVectorScore() : 0.5;
                    doc.setFinalScore(0.75 * rScore + 0.25 * vecScore);
                }

                // 2. 依据最终综合分由高到低排序，过滤掉纯垃圾切片 (score < 0.35)
                List<RagDocument> filteredAndSorted = candidates.stream()
                        .filter(d -> d.getRerankScore() == null || d.getRerankScore() >= 0.30)
                        .sorted((a, b) -> Double.compare(
                                b.getFinalScore() != null ? b.getFinalScore() : 0.0,
                                a.getFinalScore() != null ? a.getFinalScore() : 0.0
                        ))
                        .limit(targetTopK)
                        .collect(Collectors.toList());

                // 兜底：如果全部被过滤掉了，至少保留向量分最高的 1 条
                if (filteredAndSorted.isEmpty() && !candidates.isEmpty()) {
                    filteredAndSorted.add(candidates.get(0));
                }

                log.info("🎯 [RagReranker 精排完成] 初筛 {} 条 ➔ 精排选出 {} 条优质切片 (Top1得分: {})",
                        candidates.size(), filteredAndSorted.size(),
                        filteredAndSorted.isEmpty() ? "N/A" : String.format("%.3f", filteredAndSorted.get(0).getFinalScore()));

                // 3. 执行 U 型重排 (Lost-in-the-Middle 规避)
                if (enableUShaped && filteredAndSorted.size() >= 3) {
                    return applyUShapedReordering(filteredAndSorted);
                }

                return filteredAndSorted;
            }
        } catch (Exception e) {
            log.warn("⚠️ Rerank 精排异常，降级使用向量得分排序: error={}", e.getMessage());
        }

        // 降级：按原始向量分取 TopK
        return candidates.stream().limit(targetTopK).collect(Collectors.toList());
    }

    /**
     * U 型重排算法实现 (U-Shaped Context Layout)
     * 排列顺序：[Rank 1, Rank 3, ..., Rank 4, Rank 2]
     * 保证最核心的 Rank 1 处于最前，Rank 2 处于最后。
     */
    private List<RagDocument> applyUShapedReordering(List<RagDocument> rankedDocs) {
        if (rankedDocs == null || rankedDocs.size() < 3) {
            return rankedDocs;
        }

        List<RagDocument> uShaped = new ArrayList<>(rankedDocs.size());
        for (int i = 0; i < rankedDocs.size(); i++) {
            uShaped.add(null);
        }

        int left = 0;
        int right = rankedDocs.size() - 1;

        for (int i = 0; i < rankedDocs.size(); i++) {
            if (i % 2 == 0) {
                uShaped.set(left++, rankedDocs.get(i));
            } else {
                uShaped.set(right--, rankedDocs.get(i));
            }
        }

        log.debug("🧲 [U型重排完成] 已规避 Lost-in-the-Middle 效应");
        return uShaped;
    }
}