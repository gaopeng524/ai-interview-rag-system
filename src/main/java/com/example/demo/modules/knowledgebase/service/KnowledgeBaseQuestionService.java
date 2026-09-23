package com.example.demo.modules.knowledgebase.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseQuestionMapper;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseQuestionDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBaseQuestionEntity;
import com.example.demo.modules.knowledgebase.model.enums.KnowledgeBaseQuestionStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 知识库考题管理业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionService {

    private final KnowledgeBaseQuestionMapper questionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQuestionGenerationService generationService;
    private final ObjectMapper objectMapper;

    /**
     * 多维条件检索知识库题目列表
     */
    public List<KnowledgeBaseQuestionDTO.QuestionItem> listQuestions(
            Long kbId,
            KnowledgeBaseQuestionStatus status,
            String category,
            String difficulty,
            String keyword) {

        String statusStr = status != null ? status.name() : null;
        List<KnowledgeBaseQuestionEntity> entities = questionMapper.findQuestions(kbId, statusStr, category, difficulty, keyword);
        return entities.stream().map(this::toDTO).toList();
    }

    /**
     * 题目分类统计
     */
    public List<KnowledgeBaseQuestionDTO.CategoryCount> listCategories(Long kbId) {
        return questionMapper.countCategoriesByKbId(kbId);
    }

    /**
     * 提交异步出题任务
     */
    public KnowledgeBaseQuestionDTO.QuestionGenStatusResponse submitGenerationTask(
            Long kbId,
            KnowledgeBaseQuestionDTO.GenerateQuestionsRequest request) {
        return generationService.submitTask(kbId, request);
    }

    /**
     * 获取出题任务状态
     */
    public KnowledgeBaseQuestionDTO.QuestionGenStatusResponse getGenerationStatus(Long kbId) {
        return generationService.getStatus(kbId);
    }

    /**
     * 手动新增考题
     */
    @Transactional
    public KnowledgeBaseQuestionDTO.QuestionItem createQuestion(
            Long kbId,
            KnowledgeBaseQuestionDTO.SaveQuestionRequest request) {

        KnowledgeBase kb = knowledgeBaseMapper.findById(kbId);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId);
        }

        KnowledgeBaseQuestionEntity entity = KnowledgeBaseQuestionEntity.create(
                kbId, request, toJson(request.keyPoints()), toJson(request.followUps())
        );

        questionMapper.insert(entity);
        log.info("✅ 手动创建知识库考题成功: id={}, kbId={}", entity.getId(), kbId);
        return toDTO(entity);
    }

    /**
     * 编辑更新考题
     */
    @Transactional
    public KnowledgeBaseQuestionDTO.QuestionItem updateQuestion(
            Long questionId,
            KnowledgeBaseQuestionDTO.SaveQuestionRequest request) {

        KnowledgeBaseQuestionEntity entity = questionMapper.findById(questionId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "考题不存在: " + questionId);
        }

        if (request.difficulty() != null) entity.setDifficulty(request.difficulty());
        if (request.type() != null) entity.setType(request.type());
        if (request.category() != null) entity.setCategory(request.category());
        if (request.question() != null) entity.setQuestion(request.question());
        if (request.topicSummary() != null) entity.setTopicSummary(request.topicSummary());
        if (request.referenceAnswer() != null) entity.setReferenceAnswer(request.referenceAnswer());
        if (request.keyPoints() != null) entity.setKeyPoints(toJson(request.keyPoints()));
        if (request.scoringRubric() != null) entity.setScoringRubric(request.scoringRubric());
        if (request.followUps() != null) entity.setFollowUps(toJson(request.followUps()));
        if (request.status() != null) entity.setStatus(request.status());

        questionMapper.update(entity);
        log.info("✅ 更新知识库考题成功: id={}", questionId);
        return toDTO(entity);
    }

    /**
     * 快捷更新考题状态
     */
    @Transactional
    public KnowledgeBaseQuestionDTO.QuestionItem updateStatus(Long questionId, KnowledgeBaseQuestionStatus status) {
        KnowledgeBaseQuestionEntity entity = questionMapper.findById(questionId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "考题不存在: " + questionId);
        }

        entity.setStatus(status);
        questionMapper.updateStatus(questionId, status.name());
        log.info("🔄 更新知识库考题状态: id={}, status={}", questionId, status);
        return toDTO(entity);
    }

    /**
     * 删除考题
     */
    @Transactional
    public void deleteQuestion(Long questionId) {
        KnowledgeBaseQuestionEntity entity = questionMapper.findById(questionId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "考题不存在: " + questionId);
        }
        questionMapper.deleteById(questionId);
        log.info("🗑️ 删除知识库考题: id={}", questionId);
    }

    /**
     * 获取知识库面试容量（可用考题数与分类选项）
     */
    public KnowledgeBaseQuestionDTO.CapacityResponse getInterviewCapacity(
            Long kbId,
            String difficulty,
            int mainQuestionCount,
            String category) {

        List<KnowledgeBaseQuestionDTO.CategoryCount> categories = questionMapper.countCategoriesByKbId(kbId);
        int totalAvailable = questionMapper.countByKbId(kbId);

        List<KnowledgeBaseQuestionDTO.CapacityResponse.CategoryOption> catOptions = categories.stream()
                .map(c -> new KnowledgeBaseQuestionDTO.CapacityResponse.CategoryOption(c.category(), c.count()))
                .toList();

        return KnowledgeBaseQuestionDTO.CapacityResponse.of(
                kbId, category, difficulty, mainQuestionCount, catOptions, totalAvailable
        );
    }

    // ==================== 辅助转换方法 ====================

    private KnowledgeBaseQuestionDTO.QuestionItem toDTO(KnowledgeBaseQuestionEntity entity) {
        return new KnowledgeBaseQuestionDTO.QuestionItem(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getSkillId(),
                entity.getDifficulty(),
                entity.getType(),
                entity.getCategory(),
                entity.getQuestion(),
                entity.getTopicSummary(),
                entity.getReferenceAnswer(),
                parseJsonList(entity.getKeyPoints(), String.class),
                entity.getScoringRubric(),
                parseJsonList(entity.getFollowUps(), KnowledgeBaseQuestionDTO.FollowUpItem.class),
                entity.getSourceContext(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private <T> List<T> parseJsonList(String json, Class<T> clazz) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
