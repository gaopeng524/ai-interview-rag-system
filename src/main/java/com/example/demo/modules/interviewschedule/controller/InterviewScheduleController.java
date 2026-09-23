package com.example.demo.modules.interviewschedule.controller;

import com.example.demo.common.result.Result;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.interviewschedule.model.dto.CreateInterviewRequest;
import com.example.demo.modules.interviewschedule.model.dto.InterviewScheduleDTO;
import com.example.demo.modules.interviewschedule.model.dto.ParseRequest;
import com.example.demo.modules.interviewschedule.model.dto.ParseResponse;
import com.example.demo.modules.interviewschedule.model.enums.InterviewStatus;
import com.example.demo.modules.interviewschedule.service.InterviewParseService;
import com.example.demo.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理与智能解析 REST 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

    private final InterviewScheduleService scheduleService;
    private final InterviewParseService parseService;

    /**
     * 智能解析面试邀约文本（飞书/腾讯会议/Zoom/短信邮件等）
     * POST /api/interview-schedule/parse
     */
    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        log.info("接收到面试邀约解析请求: source={}", request.getSource());
        ParseResponse response = parseService.parse(request.getRawText(), request.getSource());
        return Result.success(response);
    }

    /**
     * 创建面试日程
     * POST /api/interview-schedule
     */
    @PostMapping
    public Result<InterviewScheduleDTO> create(
            @Valid @RequestBody CreateInterviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        InterviewScheduleDTO dto = scheduleService.create(request, userId);
        return Result.success(dto);
    }

    /**
     * 根据 ID 获取面试日程
     * GET /api/interview-schedule/{id}
     */
    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        InterviewScheduleDTO dto = scheduleService.getById(id, userId);
        return Result.success(dto);
    }

    /**
     * 多条件获取面试日程列表（支持状态与时间范围检索）
     * GET /api/interview-schedule
     */
    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        List<InterviewScheduleDTO> list = scheduleService.getAll(status, start, end, userId);
        return Result.success(list);
    }

    /**
     * 更新面试日程信息
     * PUT /api/interview-schedule/{id}
     */
    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateInterviewRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        InterviewScheduleDTO dto = scheduleService.update(id, request, userId);
        return Result.success(dto);
    }

    /**
     * 删除面试日程
     * DELETE /api/interview-schedule/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        scheduleService.delete(id, userId);
        return Result.success(null);
    }

    /**
     * 快捷更新日程状态
     * PATCH /api/interview-schedule/{id}/status
     */
    @PatchMapping("/{id}/status")
    public Result<InterviewScheduleDTO> updateStatus(
            @PathVariable Long id,
            @RequestParam InterviewStatus status,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        InterviewScheduleDTO dto = scheduleService.updateStatus(id, status, userId);
        return Result.success(dto);
    }
}
