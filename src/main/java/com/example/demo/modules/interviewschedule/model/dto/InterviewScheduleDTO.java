package com.example.demo.modules.interviewschedule.model.dto;

import com.example.demo.modules.interviewschedule.model.enums.InterviewStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试日程响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewScheduleDTO {
    private Long id;
    private String companyName;
    private String position;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime interviewTime;

    private String interviewType; // ONSITE, VIDEO, PHONE
    private String meetingLink;
    private Integer roundNumber;
    private String interviewer;
    private String notes;
    private InterviewStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
