package com.example.demo.modules.interview.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面试题目与作答评分实体
 */
@Data
public class InterviewQuestionEntity {

    private Long id;
    private String sessionId;
    private Integer questionIndex;
    private String question;
    private String type;
    private String category;
    private String topicSummary;
    private String userAnswer;
    private BigDecimal score;
    private String feedback;
    private Boolean isFollowUp;
    private Integer parentQuestionIndex;
    private String referenceAnswer;
    private String keyPoints;     // JSON String: List<String>
    private String scoringRubric;
    private String sourceContext;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
