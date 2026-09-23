package com.example.demo.modules.interviewschedule.model.enums;

/**
 * 面试状态枚举
 */
public enum InterviewStatus {
    PENDING,      // 待面试
    COMPLETED,    // 已完成（待反馈）
    CANCELLED,    // 已取消
    RESCHEDULED,  // 已改期
    PASSED,       // 已通过
    REJECTED      // 未通过
}
