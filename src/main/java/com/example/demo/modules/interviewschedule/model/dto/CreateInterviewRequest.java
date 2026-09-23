package com.example.demo.modules.interviewschedule.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建/更新面试日程请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInterviewRequest {

    @NotBlank(message = "公司名称不能为空")
    private String companyName;

    @NotBlank(message = "岗位名称不能为空")
    private String position;

    @NotNull(message = "面试时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime interviewTime;

    private String interviewType; // ONSITE, VIDEO, PHONE

    private String meetingLink;

    @Builder.Default
    private Integer roundNumber = 1;

    private String interviewer;

    private String notes;
}
