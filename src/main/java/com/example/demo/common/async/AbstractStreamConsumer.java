package com.example.demo.common.async;

import com.example.demo.common.constant.AsyncTaskStreamConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类
 * 统一处理消费组创建、多节点独立消费者命名、循环监听、消息 ACK 与重试机制
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    protected final StringRedisTemplate stringRedisTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName() + "-" + consumerName);
            t.setDaemon(true);
            return t;
        });

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("[Redis Stream 消费] {} 消费者已启动: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("[Redis Stream 消费] {} 消费者已停止: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        // 1. 确保 Stream 与 消费者组已创建
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKey(), groupName());
            log.info("[Redis Stream 消费] 消费者组已创建或就绪: groupName={}", groupName());
        } catch (Exception e) {
            // 组已存在时 Redis 会报 BUSYGROUP，安全忽略
            log.debug("[Redis Stream 消费] 消费者组准备就绪: groupName={}", groupName());
        }

        // 2. 循环拉取并消费消息
        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                // XREADGROUP GROUP groupName consumerName COUNT 5 BLOCK 2000 STREAMS streamKey >
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName(), consumerName),
                        StreamReadOptions.empty()
                                .count(AsyncTaskStreamConstants.BATCH_SIZE)
                                .block(Duration.ofMillis(AsyncTaskStreamConstants.POLL_TIMEOUT_MS)),
                        StreamOffset.create(streamKey(), ReadOffset.lastConsumed())
                );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record);
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[Redis Stream 消费] 消费者线程收到中断信号");
                    break;
                }
                log.error("[Redis Stream 消费] 消费消息异常: error={}", e.getMessage(), e);
                try {
                    Thread.sleep(1000); // 异常时适当休眠避免 CPU 狂转
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        RecordId recordId = record.getId();
        Map<Object, Object> valueMap = record.getValue();

        T payload;
        try {
            payload = parsePayload(valueMap);
        } catch (Exception e) {
            log.error("[Redis Stream 消费] 解析任务载荷失败: recordId={}, error={}", recordId, e.getMessage(), e);
            // 脏数据直接 ACK 移出队列，防止死循环
            stringRedisTemplate.opsForStream().acknowledge(streamKey(), groupName(), recordId);
            return;
        }

        int retryCount = 0;
        Object retryObj = valueMap.get(AsyncTaskStreamConstants.FIELD_RETRY_COUNT);
        if (retryObj != null) {
            try {
                retryCount = Integer.parseInt(String.valueOf(retryObj));
            } catch (NumberFormatException ignored) {}
        }

        try {
            // 执行实际业务处理
            handleMessage(payload);

            // 消费成功，向 Redis 发送 ACK
            stringRedisTemplate.opsForStream().acknowledge(streamKey(), groupName(), recordId);
            log.info("[Redis Stream 消费] {}任务处理完成并已确认: recordId={}", taskDisplayName(), recordId);
        } catch (Exception e) {
            log.error("[Redis Stream 消费] {}任务执行异常: retryCount={}/{}, recordId={}, error={}",
                    taskDisplayName(), retryCount, AsyncTaskStreamConstants.MAX_RETRY_COUNT, recordId, e.getMessage(), e);

            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                // 重试机制：推入一条重试次数 +1 的新消息，并确认当前老消息
                retryTask(payload, retryCount + 1, e.getMessage());
            } else {
                // 超过最大重试次数：记录终态失败
                handleExhaustedRetries(payload, e.getMessage());
            }
            // 确认当前记录
            stringRedisTemplate.opsForStream().acknowledge(streamKey(), groupName(), recordId);
        }
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(Map<Object, Object> data);

    protected abstract void handleMessage(T payload) throws Exception;

    protected abstract void retryTask(T payload, int nextRetryCount, String lastError);

    protected abstract void handleExhaustedRetries(T payload, String error);
}
