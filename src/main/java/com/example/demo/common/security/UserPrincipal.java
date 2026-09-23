package com.example.demo.common.security;

/**
 * 当前登录用户身份：由 JWT 解析得到，挂在 Authentication 的 principal 上。
 * Controller 用 {@code @AuthenticationPrincipal UserPrincipal} 直接拿，不用再查数据库。
 */
public record UserPrincipal(Long id, String username) {
}
