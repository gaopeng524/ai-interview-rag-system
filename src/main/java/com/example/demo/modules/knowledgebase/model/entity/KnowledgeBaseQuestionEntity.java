package com.example.demo.modules.knowledgebase.model.entity;

import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseQuestionDTO;
import com.example.demo.modules.knowledgebase.model.enums.KnowledgeBaseQuestionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库考题实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseQuestionEntity {

    private Long id;
    private Long knowledgeBaseId;
    private String skillId;
    private String difficulty; // EASY, MEDIUM, HARD
    private String type;       // TECHNICAL, BEHAVIORAL, SYSTEM_DESIGN
    private String category;   // 题目所属分类/面试方向
    private String question;   // 题干内容
    private String topicSummary;
    private String referenceAnswer;
    private String keyPoints;     // JSON Array string
    private String scoringRubric;
    private String followUps;     // JSON Array string
    private String sourceContext;
    private KnowledgeBaseQuestionStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KnowledgeBaseQuestionEntity create(Long kbId,
                                                   KnowledgeBaseQuestionDTO.SaveQuestionRequest req,
                                                   String keyPointsJson,
                                                   String followUpsJson) {
        return KnowledgeBaseQuestionEntity.builder()
                .knowledgeBaseId(kbId)
                .skillId("knowledge-base")
                .difficulty(req.difficulty() != null ? req.difficulty() : "MEDIUM")
                .type(req.type() != null ? req.type() : "TECHNICAL")
                .category(req.category() != null ? req.category() : "通用")
                .question(req.question())
                .topicSummary(req.topicSummary())
                .referenceAnswer(req.referenceAnswer())
                .keyPoints(keyPointsJson)
                .scoringRubric(req.scoringRubric())
                .followUps(followUpsJson)
                .sourceContext(req.sourceContext())
                .status(req.status() != null ? req.status() : KnowledgeBaseQuestionStatus.ACTIVE)
                .build();
    }
}
