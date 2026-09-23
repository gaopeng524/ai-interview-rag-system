package com.example.demo.modules.resume.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 简历多维分析评分报告实体表 (resume_analyses)
 */
@Data
public class ResumeAnalysisEntity {

    private Long id;
    private Long resumeId;
    private BigDecimal overallScore;
    private BigDecimal contentScore;
    private BigDecimal structureScore;
    private BigDecimal skillMatchScore;
    private BigDecimal expressionScore;
    private BigDecimal projectScore;
    private String summary;
    private String strengths;   // JSON String: List<String>
    private String suggestions; // JSON String: List<Suggestion>
    private LocalDateTime createdAt;

    public static ResumeAnalysisEntity create(Long resumeId,
                                            com.example.demo.modules.resume.model.dto.ResumeDTO.ResumeAnalysisResponse resp,
                                            String strengthsJson,
                                            String suggestionsJson) {
        ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
        entity.setResumeId(resumeId);
        entity.setOverallScore(BigDecimal.valueOf(resp.overallScore()).setScale(2, java.math.RoundingMode.HALF_UP));
        if (resp.scoreDetail() != null) {
            entity.setContentScore(BigDecimal.valueOf(resp.scoreDetail().contentScore()).setScale(2, java.math.RoundingMode.HALF_UP));
            entity.setStructureScore(BigDecimal.valueOf(resp.scoreDetail().structureScore()).setScale(2, java.math.RoundingMode.HALF_UP));
            entity.setSkillMatchScore(BigDecimal.valueOf(resp.scoreDetail().skillMatchScore()).setScale(2, java.math.RoundingMode.HALF_UP));
            entity.setExpressionScore(BigDecimal.valueOf(resp.scoreDetail().expressionScore()).setScale(2, java.math.RoundingMode.HALF_UP));
            entity.setProjectScore(BigDecimal.valueOf(resp.scoreDetail().projectScore()).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        entity.setSummary(resp.summary());
        entity.setStrengths(strengthsJson);
        entity.setSuggestions(suggestionsJson);
        return entity;
    }
}
