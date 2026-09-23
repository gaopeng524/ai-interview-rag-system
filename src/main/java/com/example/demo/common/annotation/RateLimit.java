package com.example.demo.common.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 多维度高精度滑动窗口限流注解
 * 支持在同一个方法上重复配置多个维度的限流策略（例如同时配置全局总控与单 IP 防刷）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /**
     * 限流维度
     */
    Dimension dimension() default Dimension.IP;

    /**
     * 窗口内最大允许请求次数
     */
    int count() default 10;

    /**
     * 时间窗口大小
     */
    long interval() default 60;

    /**
     * 时间窗口单位（默认秒）
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 每次请求消耗的令牌数（默认 1）
     */
    int permits() default 1;

    /**
     * 超限提示信息（为空时使用默认文案）
     */
    String message() default "";

    /**
     * 限流维度枚举
     */
    enum Dimension {
        /**
         * 全局限流（整个服务统一计数，常用于保护下游大模型 API 总调用频次）
         */
        GLOBAL,

        /**
         * 基于客户端 IP 限流（防刷、防爬虫）
         */
        IP,

        /**
         * 基于当前登录用户 ID 限流
         */
        USER
    }
}
