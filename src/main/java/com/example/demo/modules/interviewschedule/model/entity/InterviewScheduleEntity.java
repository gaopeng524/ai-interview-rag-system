package com.example.demo.modules.interviewschedule.model.entity;

import com.example.demo.modules.interviewschedule.model.enums.InterviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试日程持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewScheduleEntity {
    private Long id;
    private Long userId;
    private String companyName;
    private String position;
    private LocalDateTime interviewTime;
    private String interviewType; // ONSITE, VIDEO, PHONE
    private String meetingLink;
    private Integer roundNumber;
    private String interviewer;
    private String notes;
    private InterviewStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
