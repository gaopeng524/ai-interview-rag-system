package com.example.demo.modules.interview.controller;

import com.example.demo.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 语音面试控制器（提供会话列表与占位兼容，消除前端控制台 404）
 */
@Slf4j
@RestController
@RequestMapping("/api/voice-interview")
public class VoiceInterviewController {

    /**
     * 获取语音面试会话列表（支持模拟面试首页聚合查询）
     * GET /api/voice-interview/sessions
     */
    @GetMapping("/sessions")
    public Result<List<Object>> listVoiceSessions() {
        return Result.success(Collections.emptyList());
    }

    /**
     * 获取语音面试详情
     * GET /api/voice-interview/sessions/{sessionId}
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<Object> getVoiceSession(@PathVariable Long sessionId) {
        return Result.success(null);
    }
}
