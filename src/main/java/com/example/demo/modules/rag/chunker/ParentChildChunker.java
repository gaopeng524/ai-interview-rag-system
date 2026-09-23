package com.example.demo.modules.rag.chunker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 🌟 父子块级联切片器 (Parent-Child / Small-to-Big Chunking Engine)
 * 
 * 核心哲学：
 * 检索时使用小微子块 (Child: ~180 Tokens) 保证语义极度纯粹，相似度命中率极高；
 * 命中后级联换取大父块 (Parent: ~800 Tokens) 喂给大模型，保证完整因果上下文与零断章取义！
 */
@Slf4j
@Component
public class ParentChildChunker {

    private static final List<String> RECURSIVE_SEPARATORS = List.of(
            "\n\n", "\n", "。", "！", "？", "；", ";", " ", ""
    );

    public record ChunkBundle(
            String parentId,
            String parentContent,
            List<Document> childDocuments
    ) {}

    /**
     * 将长文档递归切分成父子切片集合
     *
     * @param fullContent 完整文档正文
     * @param docName     文档名（用于面包屑路径）
     * @param category    分类
     * @param kbId        知识库 ID
     * @return 适合直接写入 vector_store 的子切片列表（元数据中已内嵌 parent_id 与 parent_content）
     */
    public List<Document> chunkToParentChildDocuments(String fullContent, String docName, String category, Long kbId) {
        if (fullContent == null || fullContent.isBlank()) {
            return List.of();
        }

        String breadcrumb = String.format("【文档来源：%s ➔ 分类：%s】\n",
                docName != null ? docName : "技术文档",
                category != null ? category : "默认");

        // 1. 递归切分出大父块 (目标 650~800 字符，80 字符重叠)
        List<String> parentTexts = recursiveSplit(fullContent.trim(), 750, 80);
        List<Document> allChildDocuments = new ArrayList<>();

        for (int pIdx = 0; pIdx < parentTexts.size(); pIdx++) {
            String pRaw = parentTexts.get(pIdx);
            String parentId = "parent-" + UUID.randomUUID().toString().substring(0, 8);
            String enrichedParentContent = breadcrumb + pRaw;

            // 2. 将每个父块细切为微小子块 (目标 180 字符，30 字符重叠)
            List<String> childTexts = recursiveSplit(pRaw, 180, 30);

            for (int cIdx = 0; cIdx < childTexts.size(); cIdx++) {
                String cRaw = childTexts.get(cIdx);
                // 过滤 < 25 字符的无意义极小碎片
                if (cRaw.trim().length() < 25) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>();
                String kbIdStr = kbId != null ? String.valueOf(kbId) : "0";
                metadata.put("kb_id", kbIdStr);
                metadata.put("knowledge_base_id", kbIdStr);
                metadata.put("parent_id", parentId);
                // 将完整父块文本直接附在子块元数据中，检索命中时 0 毫秒秒级提取！
                metadata.put("parent_content", enrichedParentContent);
                metadata.put("doc_name", docName != null ? docName : "");
                metadata.put("category", category != null ? category : "");
                metadata.put("chunk_index", allChildDocuments.size());

                Document childDoc = new Document(cRaw.trim(), metadata);
                allChildDocuments.add(childDoc);
            }
        }

        log.info("✂️ [ParentChildChunker 切分完成] 文档='{}' ({}字) ➔ 生成 {} 个大父块，{} 个小微子切片",
                docName, fullContent.length(), parentTexts.size(), allChildDocuments.size());

        return allChildDocuments;
    }

    /**
     * 递归滑动窗口切分核心算法
     */
    public List<String> recursiveSplit(String text, int targetSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        if (text.length() <= targetSize) {
            chunks.add(text.trim());
            return chunks;
        }

        // 尝试在优先级最高的分隔符处寻找最佳切分点
        int splitPos = -1;
        for (String sep : RECURSIVE_SEPARATORS) {
            if (sep.isEmpty()) {
                splitPos = targetSize;
                break;
            }
            int lastIndex = text.lastIndexOf(sep, targetSize);
            if (lastIndex > targetSize / 3) {
                splitPos = lastIndex + sep.length();
                break;
            }
        }

        if (splitPos <= 0 || splitPos > text.length()) {
            splitPos = Math.min(targetSize, text.length());
        }

        String chunk = text.substring(0, splitPos).trim();
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }

        // 滑动窗口带 overlap 重叠
        int nextStart = Math.max(0, splitPos - overlap);
        if (nextStart >= text.length() || nextStart >= splitPos) {
            nextStart = splitPos;
        }

        String remaining = text.substring(nextStart);
        if (remaining.length() > overlap) {
            chunks.addAll(recursiveSplit(remaining, targetSize, overlap));
        }

        return chunks;
    }
}