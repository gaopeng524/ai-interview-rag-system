package com.example.demo.common.async;

import com.example.demo.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * Redis Stream 生产者模板基类
 * 统一封装消息推入队列与异常处理逻辑
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    protected final StringRedisTemplate stringRedisTemplate;

    protected AbstractStreamProducer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 发送异步任务到 Redis Stream
     */
    protected boolean sendTask(T payload) {
        try {
            Map<String, String> message = buildMessage(payload);
            StringRecord record = StreamRecords.string(message).withStreamKey(streamKey());
            
            RecordId recordId = stringRedisTemplate.opsForStream().add(record);

            // 队列长度修剪，防止无限制增长
            stringRedisTemplate.opsForStream().trim(streamKey(), AsyncTaskStreamConstants.STREAM_MAX_LEN);

            log.info("[Redis Stream 生产] {}任务已推入队列: {}, recordId={}",
                    taskDisplayName(), payloadIdentifier(payload), recordId);
            return true;
        } catch (Exception e) {
            log.error("[Redis Stream 生产] 发送{}任务失败: {}, error={}",
                    taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, "任务推入队列失败: " + e.getMessage());
            return false;
        }
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void onSendFailed(T payload, String error);
}
