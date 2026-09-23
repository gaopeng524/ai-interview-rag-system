package com.example.demo.modules.interviewschedule.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面试邀约文本解析请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseRequest {
    @NotBlank(message = "待解析文本不能为空")
    private String rawText;

    private String source; // feishu, tencent, zoom, other
}
