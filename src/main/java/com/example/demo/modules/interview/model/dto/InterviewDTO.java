package com.example.demo.modules.interview.model.dto;

import java.util.List;

/**
 * 模拟面试与智能考官评估领域 DTO 聚合命名空间（Record 模式）
 */
public final class InterviewDTO {

    private InterviewDTO() {}

    /** 创建面试会话请求 */
    public record CreateInterviewRequest(
            String resumeText,
            Integer questionCount,
            Long resumeId,
            Boolean forceCreate,
            String llmProvider,
            String skillId,
            String difficulty,
            String jdText,
            String requestId,
            Long knowledgeBaseId,
            String interviewCategory
    ) {}

    /** 面试会话核心详情 */
    public record InterviewSessionDTO(
            String sessionId,
            String resumeText,
            int totalQuestions,
            int currentQuestionIndex,
            List<InterviewQuestionDTO> questions,
            String status,
            Long knowledgeBaseId,
            String interviewCategory
    ) {}

    /** 单道面试题及作答评分结构 */
    public record InterviewQuestionDTO(
            int questionIndex,
            String question,
            String type,
            String category,
            String topicSummary,
            String userAnswer,
            Double score,
            String feedback,
            Boolean isFollowUp,
            Integer parentQuestionIndex,
            String referenceAnswer,
            List<String> keyPoints,
            String scoringRubric,
            String sourceContext
    ) {}

    /** 提交答案请求 */
    public record SubmitAnswerRequest(
            String sessionId,
            Integer questionIndex,
            String answer
    ) {}

    /** 提交答案响应（指示是否有下一题） */
    public record SubmitAnswerResponse(
            boolean hasNextQuestion,
            InterviewQuestionDTO nextQuestion,
            int currentIndex,
            int totalQuestions
    ) {}

    /** 当前题目响应 */
    public record CurrentQuestionResponse(
            boolean completed,
            InterviewQuestionDTO question,
            String message
    ) {}

    /** 各技术维度得分 */
    public record CategoryScoreDTO(
            String category,
            double score,
            int questionCount
    ) {}

    /** 题目评估诊断明细 */
    public record QuestionEvaluationDTO(
            int questionIndex,
            String question,
            String category,
            String userAnswer,
            double score,
            String feedback
    ) {}

    /** 标准参考答案与要点 */
    public record ReferenceAnswerDTO(
            int questionIndex,
            String question,
            String referenceAnswer,
            List<String> keyPoints
    ) {}

    /** 综合评估报告（能力雷达图、诊断、改进建议、参考答案） */
    public record InterviewReportDTO(
            String sessionId,
            int totalQuestions,
            double overallScore,
            List<CategoryScoreDTO> categoryScores,
            List<QuestionEvaluationDTO> questionDetails,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<ReferenceAnswerDTO> referenceAnswers
    ) {}

    /** 会话历史列表项（侧边栏/记录列表展示） */
    public record SessionListItemDTO(
            String sessionId,
            String skillId,
            String difficulty,
            Long resumeId,
            int totalQuestions,
            String status,
            String evaluateStatus,
            String evaluateError,
            Double overallScore,
            String sourceType,
            Long knowledgeBaseId,
            String interviewCategory,
            String createdAt,
            String completedAt
    ) {}

    /** 技能方向分类条目 */
    public record CategoryDTO(
            String key,
            String label,
            String priority,
            String ref,
            Boolean shared
    ) {}

    /** 技能方向 UI 展现配置 */
    public record DisplayDTO(
            String icon,
            String gradient,
            String iconBg,
            String iconColor
    ) {}

    /** 技能方向详情 / 列表项 */
    public record SkillDTO(
            String id,
            String name,
            String description,
            List<CategoryDTO> categories,
            boolean isPreset,
            String sourceJd,
            String persona,
            DisplayDTO display
    ) {}

    /** 解析 JD 请求 */
    public record ParseJdRequest(String jdText) {}

    /** 单题作答复盘明细 */
    public record AnswerDetailDTO(
            Integer questionIndex,
            String question,
            String category,
            String userAnswer,
            Double score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints,
            String answeredAt
    ) {}

    /** 面试会话全量复盘详情 */
    public record InterviewDetailDTO(
            Long id,
            String sessionId,
            Integer totalQuestions,
            String status,
            String evaluateStatus,
            String evaluateError,
            Double overallScore,
            String sourceType,
            Long knowledgeBaseId,
            String overallFeedback,
            String createdAt,
            String completedAt,
            List<InterviewQuestionDTO> questions,
            List<String> strengths,
            List<String> improvements,
            List<ReferenceAnswerDTO> referenceAnswers,
            List<AnswerDetailDTO> answers
    ) {}
}
