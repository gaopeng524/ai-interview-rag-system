package com.example.demo.modules.interview.controller;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.modules.interview.model.dto.InterviewDTO.*;
import com.example.demo.common.result.Result;
import com.example.demo.common.security.CurrentUser;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.interview.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模拟面试与智能考官评估控制器（薄 Controller 设计）：
 * 职责仅限于参数接收、路由映射、调用 Service 层和封装 Result 统一返回。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final com.example.demo.modules.interview.service.InterviewSkillService skillService;

    /**
     * 列出所有文字面试会话（用于面试记录与历史侧边栏）
     * GET /api/interview/sessions
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions(@AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.listSessions(userId));
    }

    /**
     * 创建面试会话
     * POST /api/interview/sessions
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60, message = "创建面试过于频繁，请稍后再试")
    public Result<InterviewSessionDTO> createSession(
            @RequestBody CreateInterviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.createSession(userId, request));
    }

    /**
     * 获取会话信息
     * GET /api/interview/sessions/{sessionId}
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.getSession(sessionId, userId));
    }

    /**
     * 获取当前待作答题目
     * GET /api/interview/sessions/{sessionId}/question
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<CurrentQuestionResponse> getCurrentQuestion(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.getCurrentQuestion(sessionId, userId));
    }

    /**
     * 提交答案并推进到下一题
     * POST /api/interview/sessions/{sessionId}/answers
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 60, message = "提交答案过于频繁，请稍后再试")
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        Integer questionIndex = body.get("questionIndex") instanceof Number n ? n.intValue() : null;
        String answer = body.get("answer") != null ? body.get("answer").toString() : "";
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        return Result.success(interviewService.submitAnswer(userId, request));
    }

    /**
     * 暂存答案（不推进到下一题）
     * PUT /api/interview/sessions/{sessionId}/answers
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        Integer questionIndex = body.get("questionIndex") instanceof Number n ? n.intValue() : null;
        String answer = body.get("answer") != null ? body.get("answer").toString() : "";
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        interviewService.saveAnswer(userId, request);
        return Result.success(null);
    }

    /**
     * 提前交卷
     * POST /api/interview/sessions/{sessionId}/complete
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        interviewService.completeInterview(sessionId, userId);
        return Result.success(null);
    }

    /**
     * 生成/获取面试深度评估报告（雷达图、诊断、参考答案）
     * GET /api/interview/sessions/{sessionId}/report
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60, message = "生成报告过于频繁，请稍后刷新")
    public Result<InterviewReportDTO> getReport(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.generateReport(sessionId, userId));
    }

    /**
     * 获取全量面试复盘详情
     * GET /api/interview/sessions/{sessionId}/details
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetails(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.getInterviewDetails(sessionId, userId));
    }

    /**
     * 导出面试评估报告
     * GET /api/interview/sessions/{sessionId}/export
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public org.springframework.http.ResponseEntity<byte[]> exportInterview(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        byte[] content = interviewService.exportInterviewPdf(sessionId, userId);
        String filename = "interview-report-" + sessionId + ".md";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /**
     * 查找未完成的面试会话（支持断点恢复）
     * GET /api/interview/sessions/unfinished/{resumeId}
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(
            @PathVariable Long resumeId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.findUnfinishedSession(userId, resumeId));
    }

    /**
     * 删除面试会话
     * DELETE /api/interview/sessions/{sessionId}
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        interviewService.deleteSession(sessionId, userId);
        return Result.success(null);
    }

    /**
     * 基于知识库创建专项面试
     * POST /api/knowledgebase-interviews/sessions
     */
    @PostMapping("/api/knowledgebase-interviews/sessions")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60, message = "创建知识库面试过于频繁，请稍后再试")
    public Result<InterviewSessionDTO> createKbInterviewSession(
            @RequestBody CreateInterviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = CurrentUser.requireId(principal);
        return Result.success(interviewService.createSession(userId, request));
    }

    // ===== 面试技能与方向 (Skill) =====

    @GetMapping("/api/interview/skills")
    public Result<List<SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    @GetMapping("/api/interview/skills/{id}")
    public Result<SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }

    @PostMapping("/api/interview/skills/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<List<CategoryDTO>> parseJd(@RequestBody ParseJdRequest request) {
        return Result.success(skillService.parseJd(request.jdText()));
    }
}
