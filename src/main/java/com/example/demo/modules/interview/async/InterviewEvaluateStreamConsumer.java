package com.example.demo.modules.interview.async;

import com.example.demo.common.async.AbstractStreamConsumer;
import com.example.demo.common.constant.AsyncTaskStreamConstants;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import com.example.demo.modules.interview.model.entity.InterviewSessionEntity;
import com.example.demo.modules.interview.service.InterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估 Redis Stream 异步任务消费者
 */
@Slf4j
@Component
public class InterviewEvaluateStreamConsumer extends AbstractStreamConsumer<InterviewEvaluateStreamConsumer.EvaluateTaskPayload> {

    private final InterviewService interviewService;
    private final InterviewMapper interviewMapper;
    private final org.redisson.api.RedissonClient redissonClient;

    public record EvaluateTaskPayload(String sessionId, Long userId) {}

    public InterviewEvaluateStreamConsumer(
            StringRedisTemplate stringRedisTemplate,
            @Lazy InterviewService interviewService,
            InterviewMapper interviewMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            org.redisson.api.RedissonClient redissonClient) {
        super(stringRedisTemplate);
        this.interviewService = interviewService;
        this.interviewMapper = interviewMapper;
        this.redissonClient = redissonClient;
    }

    @Override
    protected String taskDisplayName() {
        return "面试综合评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP;
    }

    @Override
    protected String consumerPrefix() {
        return "interview-eval-worker";
    }

    @Override
    protected String threadName() {
        return "redis-stream-evaluate";
    }

    @Override
    protected EvaluateTaskPayload parsePayload(Map<Object, Object> data) {
        Object sessionIdObj = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        Object userIdObj = data.get(AsyncTaskStreamConstants.FIELD_USER_ID);

        if (sessionIdObj == null || userIdObj == null) {
            throw new IllegalArgumentException("消息缺失必要字段 session_id 或 user_id");
        }

        String sessionId = String.valueOf(sessionIdObj);
        Long userId = Long.parseLong(String.valueOf(userIdObj));
        return new EvaluateTaskPayload(sessionId, userId);
    }

    @Override
    protected void handleMessage(EvaluateTaskPayload payload) throws Exception {
        String sessionId = payload.sessionId();
        Long userId = payload.userId();

        try {
            // 幂等检查：若会话已完成评估，则跳过
            InterviewSessionEntity session = interviewMapper.findSessionBySessionId(sessionId);
            if (session == null) {
                log.warn("[Redis Stream 消费] 面试会话不存在，跳过评估: sessionId={}", sessionId);
                return;
            }
            if ("COMPLETED".equals(session.getEvaluateStatus()) && session.getOverallScore() != null) {
                log.info("[Redis Stream 消费] 会话评估已完成，无需重复评估: sessionId={}", sessionId);
                return;
            }

            log.info("🚀 [Redis Stream 消费] 开始异步执行大模型面试综合评估: sessionId={}", sessionId);
            interviewService.generateReport(sessionId, userId);
            log.info("✅ [Redis Stream 消费] 面试综合评估完成: sessionId={}", sessionId);
        } finally {
            // 消费完成后释放分布式锁
            releaseLock(sessionId);
        }
    }

    private void releaseLock(String sessionId) {
        String lockKey = AsyncTaskStreamConstants.LOCK_INTERVIEW_EVALUATE_PREFIX + sessionId;
        if (redissonClient != null) {
            try {
                org.redisson.api.RLock lock = redissonClient.getLock(lockKey);
                if (lock.isLocked()) {
                    lock.forceUnlock();
                }
            } catch (Exception e) {
                stringRedisTemplate.delete(lockKey);
            }
        } else {
            stringRedisTemplate.delete(lockKey);
        }
    }

    @Override
    protected void retryTask(EvaluateTaskPayload payload, int nextRetryCount, String lastError) {
        try {
            StringRecord record = StreamRecords.string(Map.of(
                    AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
                    AsyncTaskStreamConstants.FIELD_USER_ID, String.valueOf(payload.userId()),
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(nextRetryCount),
                    AsyncTaskStreamConstants.FIELD_ERROR, lastError != null ? lastError : ""
            )).withStreamKey(streamKey());

            stringRedisTemplate.opsForStream().add(record);
            log.warn("[Redis Stream 重试] 已重新投递面试评估任务: sessionId={}, nextRetryCount={}", payload.sessionId(), nextRetryCount);
        } catch (Exception e) {
            log.error("[Redis Stream 重试] 重新投递评估任务失败: sessionId={}, error={}", payload.sessionId(), e.getMessage(), e);
        }
    }

    @Override
    protected void handleExhaustedRetries(EvaluateTaskPayload payload, String error) {
        log.error("[Redis Stream 终态失败] 面试评估任务超过最大重试次数: sessionId={}, error={}", payload.sessionId(), error);
        try {
            InterviewSessionEntity session = interviewMapper.findSessionBySessionId(payload.sessionId());
            if (session != null) {
                session.setEvaluateStatus("FAILED");
                session.setEvaluateError(error);
                interviewMapper.updateSessionEvaluation(session);
            }
        } catch (Exception e) {
            log.error("[Redis Stream 终态失败] 更新会话状态失败: sessionId={}, error={}", payload.sessionId(), e.getMessage());
        } finally {
            releaseLock(payload.sessionId());
        }
    }
}
