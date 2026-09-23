package com.example.demo.modules.interviewschedule.service;

import com.example.demo.modules.interviewschedule.mapper.InterviewScheduleMapper;
import com.example.demo.modules.interviewschedule.model.enums.InterviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 面试日程状态定时巡检器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleMapper scheduleMapper;

    /**
     * 每小时定时巡检，将已过期超过24小时仍处于待面试 PENDING 状态的日程置为 CANCELLED
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        int updated = scheduleMapper.updateExpiredStatus(
                InterviewStatus.CANCELLED.name(),
                InterviewStatus.PENDING.name(),
                threshold
        );

        if (updated > 0) {
            log.info("⏰ [日程定时巡检] 已自动将 {} 条逾期未处理的面试日程更新为已取消", updated);
        }
    }
}
