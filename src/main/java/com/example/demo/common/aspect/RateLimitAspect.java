package com.example.demo.common.aspect;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.common.annotation.RateLimits;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.security.UserPrincipal;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 多维度高精度滑动窗口限流切面
 * 基于 Redis + Lua 脚本执行原子检查与扣减
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private DefaultRedisScript<Long> rateLimitScript;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("scripts/rate_limit_single.lua"));
        rateLimitScript.setResultType(Long.class);
    }

    @Around("@annotation(com.example.demo.common.annotation.RateLimit) || @annotation(com.example.demo.common.annotation.RateLimits)")
    public Object handleRateLimit(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 获取方法上的所有 RateLimit 规则
        List<RateLimit> rateLimits = getRateLimitAnnotations(method);
        if (rateLimits.isEmpty()) {
            return joinPoint.proceed();
        }

        String targetClassName = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();

        long nowMs = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().replace("-", "");

        args.add(String.valueOf(nowMs));
        args.add(requestId);
        args.add(String.valueOf(rateLimits.size()));

        for (RateLimit rateLimit : rateLimits) {
            String dimensionKey = buildDimensionKey(targetClassName, methodName, rateLimit.dimension());
            keys.add(dimensionKey);

            long intervalMs = rateLimit.timeUnit().toMillis(rateLimit.interval());
            args.add(String.valueOf(rateLimit.permits()));
            args.add(String.valueOf(intervalMs));
            args.add(String.valueOf(rateLimit.count()));
        }

        try {
            Long result = stringRedisTemplate.execute(rateLimitScript, keys, args.toArray());
            if (result == null || result < 1) {
                int failedIndex = result != null ? (int) Math.abs(result) - 1 : 0;
                RateLimit failedRule = (failedIndex >= 0 && failedIndex < rateLimits.size()) 
                        ? rateLimits.get(failedIndex) 
                        : rateLimits.get(0);

                String errorMsg = (failedRule.message() != null && !failedRule.message().isBlank())
                        ? failedRule.message()
                        : "请求过于频繁，请稍后再试";

                log.warn("[限流拦截] 接口被限流: method={}.{}, dimension={}, key={}",
                        targetClassName, methodName, failedRule.dimension(), keys.get(failedIndex));

                throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, errorMsg);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("[限流异常] Redis限流执行失败，安全放行: {}", e.getMessage(), e);
            // 限流组件异常时采用优雅降级（放行），避免因 Redis 波动阻断核心业务
        }

        return joinPoint.proceed();
    }

    private List<RateLimit> getRateLimitAnnotations(Method method) {
        List<RateLimit> list = new ArrayList<>();
        RateLimits rateLimits = method.getAnnotation(RateLimits.class);
        if (rateLimits != null) {
            list.addAll(Arrays.asList(rateLimits.value()));
        } else {
            RateLimit single = method.getAnnotation(RateLimit.class);
            if (single != null) {
                list.add(single);
            }
        }
        return list;
    }

    private String buildDimensionKey(String className, String methodName, RateLimit.Dimension dimension) {
        String base = "ratelimit:" + className + ":" + methodName;
        return switch (dimension) {
            case GLOBAL -> base + ":global";
            case IP -> base + ":ip:" + getClientIp();
            case USER -> base + ":user:" + getCurrentUserId();
        };
    }

    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return (ip != null && !ip.isBlank()) ? ip : "127.0.0.1";
    }

    private String getCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
                return String.valueOf(principal.id());
            }
        } catch (Exception ignored) {}
        return "anonymous";
    }
}
