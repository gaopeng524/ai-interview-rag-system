package com.example.demo.modules.interview.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面试会话实体
 */
@Data
public class InterviewSessionEntity {

    private Long id;
    private String sessionId;
    private Long userId;
    private String skillId;
    private String difficulty;
    private Long resumeId;
    private String resumeText;
    private String jdText;
    private Integer totalQuestions;
    private Integer currentQuestionIndex;
    private String status;
    private String evaluateStatus;
    private String evaluateError;
    private BigDecimal overallScore;
    private String overallFeedback;
    private String strengths;       // JSON String: List<String>
    private String improvements;    // JSON String: List<String>
    private String categoryScores;  // JSON String: List<CategoryScoreDTO>
    private String sourceType;
    private Long knowledgeBaseId;
    private String interviewCategory;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
