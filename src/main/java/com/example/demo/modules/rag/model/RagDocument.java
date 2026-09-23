package com.example.demo.modules.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.io.Serializable;
import java.util.Map;

/**
 * RAG 统一通用切片实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String text;
    private Map<String, Object> metadata;
    
    /** 原始向量相似度得分 (0.0 ~ 1.0) */
    private Double vectorScore;
    
    /** 重排精排得分 (0.0 ~ 1.0) */
    private Double rerankScore;
    
    /** 最终综合评分 */
    private Double finalScore;

    public static RagDocument fromSpringAiDocument(Document doc) {
        if (doc == null) return null;
        Double score = doc.getScore() != null ? doc.getScore() : 0.0;
        return RagDocument.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(doc.getMetadata())
                .vectorScore(score)
                .finalScore(score)
                .build();
    }

    public Document toSpringAiDocument() {
        return Document.builder()
                .id(this.id)
                .text(this.text)
                .metadata(this.metadata != null ? this.metadata : Map.of())
                .score(this.finalScore != null ? this.finalScore : this.vectorScore)
                .build();
    }
}