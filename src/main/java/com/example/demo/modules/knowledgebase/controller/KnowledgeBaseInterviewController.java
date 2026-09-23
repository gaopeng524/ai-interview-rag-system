package com.example.demo.modules.knowledgebase.controller;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.common.result.Result;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.interview.model.dto.InterviewDTO;
import com.example.demo.modules.interview.service.InterviewService;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseQuestionDTO;
import com.example.demo.modules.knowledgebase.model.enums.KnowledgeBaseQuestionStatus;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库面试与考题管理控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseInterviewController {

    private final KnowledgeBaseQuestionService questionService;
    private final InterviewService interviewService;

    /**
     * 获取指定知识库的考题列表（支持状态、分类、难度、关键词搜索）
     * GET /api/knowledgebase/{id}/questions
     */
    @GetMapping("/api/knowledgebase/{id}/questions")
    public Result<List<KnowledgeBaseQuestionDTO.QuestionItem>> listQuestions(
            @PathVariable Long id,
            @RequestParam(required = false) KnowledgeBaseQuestionStatus status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String keyword) {
        return Result.success(questionService.listQuestions(id, status, category, difficulty, keyword));
    }

    /**
     * 获取知识库题目的分类统计
     * GET /api/knowledgebase/{id}/questions/categories
     */
    @GetMapping("/api/knowledgebase/{id}/questions/categories")
    public Result<List<KnowledgeBaseQuestionDTO.CategoryCount>> listCategories(@PathVariable Long id) {
        return Result.success(questionService.listCategories(id));
    }

    /**
     * 触发异步从知识库提炼考题任务
     * POST /api/knowledgebase/{id}/questions/generate
     */
    @PostMapping("/api/knowledgebase/{id}/questions/generate")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 60)
    public Result<KnowledgeBaseQuestionDTO.QuestionGenStatusResponse> generateQuestions(
            @PathVariable Long id,
            @RequestBody(required = false) KnowledgeBaseQuestionDTO.GenerateQuestionsRequest request) {
        log.info("接收到知识库智能提炼考题请求: kbId={}", id);
        return Result.success(questionService.submitGenerationTask(id, request));
    }

    /**
     * 查询知识库出题任务进度与状态
     * GET /api/knowledgebase/{id}/questions/generation-status
     */
    @GetMapping("/api/knowledgebase/{id}/questions/generation-status")
    public Result<KnowledgeBaseQuestionDTO.QuestionGenStatusResponse> getQuestionGenerationStatus(
            @PathVariable Long id) {
        return Result.success(questionService.getGenerationStatus(id));
    }

    /**
     * 手动新增考题
     * POST /api/knowledgebase/{id}/questions
     */
    @PostMapping("/api/knowledgebase/{id}/questions")
    public Result<KnowledgeBaseQuestionDTO.QuestionItem> createQuestion(
            @PathVariable Long id,
            @RequestBody KnowledgeBaseQuestionDTO.SaveQuestionRequest request) {
        return Result.success(questionService.createQuestion(id, request));
    }

    /**
     * 更新考题信息
     * PUT /api/knowledgebase/questions/{questionId}
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}")
    public Result<KnowledgeBaseQuestionDTO.QuestionItem> updateQuestion(
            @PathVariable Long questionId,
            @RequestBody KnowledgeBaseQuestionDTO.SaveQuestionRequest request) {
        return Result.success(questionService.updateQuestion(questionId, request));
    }

    /**
     * 快捷修改考题掌握状态
     * PUT /api/knowledgebase/questions/{questionId}/status
     */
    @PutMapping("/api/knowledgebase/questions/{questionId}/status")
    public Result<KnowledgeBaseQuestionDTO.QuestionItem> updateQuestionStatus(
            @PathVariable Long questionId,
            @RequestBody KnowledgeBaseQuestionDTO.UpdateStatusRequest request) {
        return Result.success(questionService.updateStatus(questionId, request != null ? request.status() : null));
    }

    /**
     * 删除考题
     * DELETE /api/knowledgebase/questions/{questionId}
     */
    @DeleteMapping("/api/knowledgebase/questions/{questionId}")
    public Result<Void> deleteQuestion(@PathVariable Long questionId) {
        questionService.deleteQuestion(questionId);
        return Result.success(null);
    }

    /**
     * 获取知识库面试可用容量与分类选项
     * GET /api/knowledgebase/{id}/interview-capacity
     */
    @GetMapping("/api/knowledgebase/{id}/interview-capacity")
    public Result<KnowledgeBaseQuestionDTO.CapacityResponse> getInterviewCapacity(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "MEDIUM") String difficulty,
            @RequestParam(required = false, defaultValue = "5") int mainQuestionCount,
            @RequestParam(required = false) String category) {
        return Result.success(questionService.getInterviewCapacity(id, difficulty, mainQuestionCount, category));
    }
}
