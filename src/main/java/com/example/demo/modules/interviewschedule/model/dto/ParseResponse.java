package com.example.demo.modules.interviewschedule.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面试邀约文本解析响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResponse {
    private Boolean success;
    private CreateInterviewRequest data;
    private Double confidence;
    private String parseMethod; // rule, ai
    private String log;
}
