package com.example.demo.common.exception;

import lombok.Getter;

/**
 * 业务层主动抛出的异常，自带业务错误码（来自 ErrorCode）。
 * 例如登录失败应抛 401（UNAUTHORIZED），而不是被全局兜底处理成 500（服务器内部错误）。
 */
@Getter
public class BusinessException extends RuntimeException {
    /** 业务错误码（来自 ErrorCode 枚举，全局唯一） */
    private final Integer code;
    /** 展示给用户的错误文案 */
    private final String message;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
    }
}
