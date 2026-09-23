package com.example.demo.modules.user.model.dto;

/**
 * 用户认证与信息聚合 DTO
 */
public class UserDTO {

    // ========== 请求 DTO ==========

    /**
     * 登录请求
     */
    public record LoginRequest(
            String username,
            String password
    ) {}

    // ========== 响应 DTO ==========

    /**
     * 登录成功响应
     */
    public record LoginResponse(
            String token,
            UserResponse user
    ) {}

    /**
     * 用户脱敏信息响应
     */
    public record UserResponse(
            Long id,
            String username
    ) {}
}
