package com.example.demo.common.annotation;

import java.lang.annotation.*;

/**
 * {@link RateLimit} 的可重复注解容器
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimits {
    RateLimit[] value();
}
