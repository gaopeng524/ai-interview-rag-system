package com.example.demo.common.security;

/**
 * 用户角色。数据库 users.role 存枚举名（USER / ADMIN），
 * Spring Security 中对应的 authority 为 ROLE_USER / ROLE_ADMIN。
 */
public enum Role {
    USER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    /** 宽松解析：null/空/未知值一律视为 USER（最小权限），绝不因脏数据误给 ADMIN。 */
    public static Role fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
