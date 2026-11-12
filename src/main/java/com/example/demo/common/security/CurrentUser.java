package com.example.demo.common.security;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;

/**
 * 从 {@link UserPrincipal} 取当前用户 id 的唯一入口。
 *
 * 以前 Controller 里普遍写 {@code principal != null ? principal.id() : 1L}：
 * 一旦某个路径被 permitAll，匿名请求就会被当成 1 号用户，能读写 1 号用户的数据。
 * 现在统一改为：拿不到登录用户就直接 401，绝不回退到默认用户。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long requireId(UserPrincipal principal) {
        if (principal == null || principal.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return principal.id();
    }
}
