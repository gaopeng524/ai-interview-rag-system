package com.example.demo.modules.knowledgebase.model.dto;

import com.example.demo.modules.knowledgebase.model.enums.KnowledgeBaseQuestionStatus;
import com.example.demo.modules.knowledgebase.model.enums.QuestionGenStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 知识库考题领域 DTO 聚合命名空间（基于 Java 21 Record 纯净模式）
 */
public final class KnowledgeBaseQuestionDTO {

    private KnowledgeBaseQuestionDTO() {}

    /** 追问项 DTO */
    public record FollowUpItem(
            String question,
            String referenceAnswer,
            List<String> keyPoints
    ) {}

    /** 单题详情 / 列表项 DTO（对齐前端接口） */
    public record QuestionItem(
            Long id,
            Long knowledgeBaseId,
            String skillId,
            String difficulty,
            String type,
            String category,
            String question,
            String topicSummary,
            String referenceAnswer,
            List<String> keyPoints,
            String scoringRubric,
            List<FollowUpItem> followUps,
            String sourceContext,
            KnowledgeBaseQuestionStatus status,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
    ) {}

    /** 触发异步生成考题请求 */
    public record GenerateQuestionsRequest(
            String difficulty,
            Integer questionCount,
            Integer followUpCount,
            Integer categoryLimit,
            String llmProvider
    ) {}

    /** 生成任务配置 */
    public record GenerationConfig(
            String difficulty,
            Integer questionCount,
            Integer followUpCount,
            Integer categoryLimit,
            String llmProvider
    ) {
        public static GenerationConfig of(String difficulty, int count, GenerateQuestionsRequest req) {
            return new GenerationConfig(
                    difficulty,
                    count,
                    req != null && req.followUpCount() != null ? req.followUpCount() : 1,
                    req != null && req.categoryLimit() != null ? req.categoryLimit() : 5,
                    req != null ? req.llmProvider() : null
            );
        }
    }

    /** 生成状态响应 */
    public record QuestionGenStatusResponse(
            Long knowledgeBaseId,
            QuestionGenStatus questionGenStatus,
            String questionGenTaskId,
            GenerationConfig questionGenConfig,
            int savedCount,
            int skippedCount,
            String message,
            String error,
            String updatedAt
    ) {
        public static QuestionGenStatusResponse init(Long kbId, int count) {
            return new QuestionGenStatusResponse(
                    kbId, QuestionGenStatus.NONE, null, null,
                    count, 0, "未开始出题任务", null, now()
            );
        }

        public static QuestionGenStatusResponse processing(Long kbId, String taskId, GenerationConfig config) {
            return new QuestionGenStatusResponse(
                    kbId, QuestionGenStatus.PROCESSING, taskId, config,
                    0, 0, "正在从知识库内容中提炼考题...", null, now()
            );
        }

        public static QuestionGenStatusResponse completed(Long kbId, String taskId, GenerationConfig config, int count) {
            return new QuestionGenStatusResponse(
                    kbId, QuestionGenStatus.COMPLETED, taskId, config,
                    count, 0, "成功提炼 " + count + " 道考题", null, now()
            );
        }

        public static QuestionGenStatusResponse failed(Long kbId, String taskId, GenerationConfig config, String error) {
            return new QuestionGenStatusResponse(
                    kbId, QuestionGenStatus.FAILED, taskId, config,
                    0, 0, "提炼考题失败", error, now()
            );
        }

        private static String now() {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    /** 保存/更新考题请求 */
    public record SaveQuestionRequest(
            String difficulty,
            String type,
            String category,
            String question,
            String topicSummary,
            String referenceAnswer,
            List<String> keyPoints,
            String scoringRubric,
            List<FollowUpItem> followUps,
            String sourceContext,
            KnowledgeBaseQuestionStatus status
    ) {}

    /** 更新状态请求 */
    public record UpdateStatusRequest(
            KnowledgeBaseQuestionStatus status
    ) {}

    /** 分类统计 DTO */
    public record CategoryCount(
            String category,
            Integer count
    ) {}

    /** 面试容量响应 */
    public record CapacityResponse(
            Long knowledgeBaseId,
            String category,
            String difficulty,
            int mainQuestionCount,
            List<CategoryOption> categories,
            List<FollowUpOption> followUpOptions
    ) {
        public static CapacityResponse of(Long kbId, String category, String difficulty, int mainCount,
                                          List<CategoryOption> catOptions, int totalAvailable) {
            List<FollowUpOption> followUpOptions = List.of(
                    new FollowUpOption(0, totalAvailable, true),
                    new FollowUpOption(1, totalAvailable, true),
                    new FollowUpOption(2, totalAvailable, true)
            );
            return new CapacityResponse(
                    kbId, category, difficulty, mainCount, catOptions, followUpOptions
            );
        }

        public record CategoryOption(String category, int availableQuestionCount) {}
        public record FollowUpOption(int followUpCount, int availableQuestionCount, boolean selectable) {}
    }

    /** 基于知识库发起面试请求 */
    public record CreateKbInterviewRequest(
            Long knowledgeBaseId,
            String category,
            String difficulty,
            Integer mainQuestionCount,
            Integer followUpCount,
            String llmProvider
    ) {}
}
