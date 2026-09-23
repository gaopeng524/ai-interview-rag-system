package com.example.demo.modules.resume.model.dto;

import java.util.List;

/**
 * 简历领域数据传输对象 (DTO) 集合
 */
public final class ResumeDTO {

    private ResumeDTO() {}

    /** 简历评分明细 (五维模型) */
    public record ScoreDetail(
            double contentScore,      // 内容完整性 (0-25)
            double structureScore,    // 结构清晰度 (0-20)
            double skillMatchScore,   // 技能匹配度 (0-25)
            double expressionScore,   // 表达专业性 (0-15)
            double projectScore       // 项目经验 (0-15)
    ) {}

    /** 单条修改建议 (STAR 法则) */
    public record Suggestion(
            String category,         // 建议类别 (如: 项目经验, 技能陈述, 格式排版)
            String priority,         // 优先级: 高 | 中 | 低
            String issue,            // 问题描述
            String recommendation    // 具体改进建议与示范
    ) {}

    /** 简历深度分析报告响应 */
    public record ResumeAnalysisResponse(
            double overallScore,
            ScoreDetail scoreDetail,
            String summary,
            List<String> strengths,
            List<Suggestion> suggestions,
            String originalText
    ) {}

    /** 存储元数据 */
    public record StorageInfo(
            String fileKey,
            String fileUrl,
            Long resumeId
    ) {}

    /** 简历上传 API 完整响应 */
    public record UploadResponse(
            ResumeAnalysisResponse analysis,
            StorageInfo storage,
            boolean duplicate,
            String message
    ) {}

    /** 简历列表项 DTO (用于列表页展示) */
    public record ResumeListItem(
            Long id,
            String filename,
            Long fileSize,
            String uploadedAt,
            int accessCount,
            Double latestScore,
            String lastAnalyzedAt,
            int interviewCount,
            String analyzeStatus,
            String analyzeError,
            String storageUrl
    ) {
        public static ResumeListItem from(com.example.demo.modules.resume.model.entity.ResumeEntity r,
                                          java.time.format.DateTimeFormatter formatter) {
            return new ResumeListItem(
                    r.getId(),
                    r.getFilename(),
                    r.getFileSize(),
                    r.getCreatedAt() != null ? r.getCreatedAt().format(formatter) : null,
                    r.getAccessCount() != null ? r.getAccessCount() : 0,
                    r.getLatestScore() != null ? r.getLatestScore().doubleValue() : null,
                    r.getUpdatedAt() != null ? r.getUpdatedAt().format(formatter) : null,
                    r.getInterviewCount() != null ? r.getInterviewCount() : 0,
                    r.getAnalyzeStatus(),
                    r.getAnalyzeError(),
                    "/api/resumes/" + r.getId()
            );
        }
    }

    /** 单次历史分析记录项 */
    public record AnalysisItem(
            Long id,
            double overallScore,
            double contentScore,
            double structureScore,
            double skillMatchScore,
            double expressionScore,
            double projectScore,
            String summary,
            String analyzedAt,
            List<String> strengths,
            List<Suggestion> suggestions
    ) {}

    /** 关联面试记录项 */
    public record InterviewItemDTO(
            Long id,
            String sessionId,
            int totalQuestions,
            String status,
            String evaluateStatus,
            String evaluateError,
            Double overallScore,
            String overallFeedback,
            String createdAt,
            String completedAt
    ) {
        public static InterviewItemDTO from(com.example.demo.modules.interview.model.entity.InterviewSessionEntity s,
                                            java.time.format.DateTimeFormatter formatter) {
            return new InterviewItemDTO(
                    s.getId(),
                    s.getSessionId(),
                    s.getTotalQuestions() != null ? s.getTotalQuestions() : 0,
                    s.getStatus(),
                    s.getEvaluateStatus(),
                    s.getEvaluateError(),
                    s.getOverallScore() != null ? s.getOverallScore().doubleValue() : null,
                    s.getOverallFeedback(),
                    s.getCreatedAt() != null ? s.getCreatedAt().format(formatter) : null,
                    s.getCompletedAt() != null ? s.getCompletedAt().format(formatter) : null
            );
        }
    }

    /** 简历全量详情 DTO (包含原始文本、分析历史与面试记录) */
    public record ResumeDetailDTO(
            Long id,
            String filename,
            Long fileSize,
            String contentType,
            String storageUrl,
            String uploadedAt,
            int accessCount,
            Double latestScore,
            int interviewCount,
            String analyzeStatus,
            String analyzeError,
            String resumeText,
            List<AnalysisItem> analyses,
            List<InterviewItemDTO> interviews
    ) {
        public static ResumeDetailDTO of(com.example.demo.modules.resume.model.entity.ResumeEntity r,
                                         List<AnalysisItem> history,
                                         List<InterviewItemDTO> interviews,
                                         java.time.format.DateTimeFormatter formatter) {
            return new ResumeDetailDTO(
                    r.getId(),
                    r.getFilename(),
                    r.getFileSize(),
                    r.getContentType(),
                    "/api/resumes/" + r.getId(),
                    r.getCreatedAt() != null ? r.getCreatedAt().format(formatter) : null,
                    r.getAccessCount() != null ? r.getAccessCount() : 0,
                    r.getLatestScore() != null ? r.getLatestScore().doubleValue() : null,
                    r.getInterviewCount() != null ? r.getInterviewCount() : 0,
                    r.getAnalyzeStatus(),
                    r.getAnalyzeError(),
                    r.getResumeText(),
                    history,
                    interviews
            );
        }
    }
}
