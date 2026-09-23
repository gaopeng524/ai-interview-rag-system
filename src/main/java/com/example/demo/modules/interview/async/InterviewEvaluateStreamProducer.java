package com.example.demo.modules.interview.async;

import com.example.demo.common.async.AbstractStreamProducer;
import com.example.demo.common.constant.AsyncTaskStreamConstants;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 面试评估 Redis Stream 异步任务生产者（集成 Redisson 工业级分布式排重锁与 Watchdog 看门狗）
 */
@Slf4j
@Component
public class InterviewEvaluateStreamProducer extends AbstractStreamProducer<InterviewEvaluateStreamProducer.EvaluateTaskPayload> {

    private final InterviewMapper interviewMapper;
    private final RedissonClient redissonClient;

    public record EvaluateTaskPayload(String sessionId, Long userId) {}

    public InterviewEvaluateStreamProducer(
            StringRedisTemplate stringRedisTemplate,
            InterviewMapper interviewMapper,
            RedissonClient redissonClient) {
        super(stringRedisTemplate);
        this.interviewMapper = interviewMapper;
        this.redissonClient = redissonClient;
    }

    /**
     * 发送面试评估异步任务（使用 Redisson 工业级分布式排重锁，防止重复投递与多次消耗 Token）
     */
    public boolean sendEvaluateTask(String sessionId, Long userId) {
        String lockKey = AsyncTaskStreamConstants.LOCK_INTERVIEW_EVALUATE_PREFIX + sessionId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试非阻塞获取排重锁（0 秒等待，leaseTime 设为 -1 激活 Redisson Watchdog 看门狗自动续期）
            if (!lock.tryLock(0, -1, TimeUnit.SECONDS)) {
                log.info("🔒 [Redisson 分布式锁拦截] 面试评估任务已在处理中，成功拦截重复投递: sessionId={}", sessionId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Redisson 加锁中断: sessionId={}", sessionId);
            return false;
        }

        // 推入 Redis Stream 队列
        boolean success = sendTask(new EvaluateTaskPayload(sessionId, userId));
        if (!success) {
            // 推送失败时主动释放锁以允许重试
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return success;
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
    protected Map<String, String> buildMessage(EvaluateTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
                AsyncTaskStreamConstants.FIELD_USER_ID, String.valueOf(payload.userId()),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(EvaluateTaskPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void onSendFailed(EvaluateTaskPayload payload, String error) {
        log.error("[Redis Stream 生产失败] 面试评估任务发送失败: sessionId={}, error={}", payload.sessionId(), error);
    }
}
