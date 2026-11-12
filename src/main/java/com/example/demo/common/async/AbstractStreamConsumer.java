package com.example.demo.common.async;

import com.example.demo.common.constant.AsyncTaskStreamConstants;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XClaimArgs;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Redis Stream 消费者模板基类
 * 统一处理消费组创建、多节点独立消费者命名、循环监听、消息 ACK 与重试机制，
 * 以及「超时未确认消息」的回收。
 *
 * <h3>为什么需要回收</h3>
 * XREADGROUP 读到消息后，消息进入该消费者的 PEL（Pending Entries List），直到 XACK 才移除。
 * 如果消费者在处理中途进程崩溃 / 被 kill / 机器宕机，这条消息永远留在 PEL 里：
 * 新启动的实例消费者名是随机的（见 {@link #init()}），用 {@code >} 只能读到新消息，旧消息就"丢"了。
 *
 * <h3>回收流程（在消费线程里定时执行，与正常消费串行，不引入并发）</h3>
 * <ol>
 *   <li>XAUTOCLAIM：把 idle 超过 {@code min-idle-seconds}（默认 60s）的 pending 消息认领到当前消费者；</li>
 *   <li>XPENDING：查出这些消息的投递次数（XAUTOCLAIM 每认领一次 +1）；</li>
 *   <li>投递次数超过 {@code max-deliveries} → 调 {@link #handleExhaustedRetries} 记为失败并 ACK（毒消息不再无限循环）；</li>
 *   <li>否则重新走 {@link #processRecord}。</li>
 * </ol>
 *
 * <h3>慢任务不会被误抢：处理中心跳</h3>
 * 只按 idle 时间回收有个坑：一个正常但很慢（比如 LLM 调用超过 60s）的任务会被别的节点抢走重复执行。
 * 因此处理期间会定时对自己正在处理的消息执行 {@code XCLAIM ... JUSTID}（认领给自己、min-idle=0）。
 * 这会把 idle 清零，且 JUSTID 模式<b>不会</b>增加投递次数。进程真的崩溃后心跳随之停止，
 * idle 才会持续增长并被回收。心跳最长持续 {@code processing-timeout-seconds}（默认 10 分钟），
 * 防止线程卡死的任务永远占着消息。
 *
 * <h3>幂等要求</h3>
 * 回收意味着「至少一次」投递：同一任务可能被执行多次（崩溃前已写了一半，或 GC 停顿超过 min-idle）。
 * 子类的 {@link #handleMessage} 必须是幂等的，具体做法见各子类注释。
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    protected final StringRedisTemplate stringRedisTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private ScheduledExecutorService heartbeatScheduler;
    private String consumerName;

    private ReclaimSettings reclaimSettings = ReclaimSettings.defaults();
    private long lastReclaimAt = 0L;

    /**
     * 回收相关配置，均可在 application.yml 中覆盖。
     *
     * @param enabled                是否开启回收
     * @param minIdle                pending 消息 idle 超过多久才回收（XAUTOCLAIM min-idle-time）
     * @param interval               回收扫描间隔
     * @param batchSize              单次 XAUTOCLAIM 最多认领条数
     * @param maxDeliveries          最大投递次数（含首次投递），超过记为失败并 ACK
     * @param processingTimeout      单条消息最长心跳续期时间
     */
    public record ReclaimSettings(boolean enabled, Duration minIdle, Duration interval, int batchSize,
                                  int maxDeliveries, Duration processingTimeout) {
        public static ReclaimSettings defaults() {
            return new ReclaimSettings(true, Duration.ofSeconds(60), Duration.ofSeconds(30), 10, 5,
                    Duration.ofMinutes(10));
        }
    }

    protected AbstractStreamConsumer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Autowired(required = false)
    void injectReclaimSettings(
            @Value("${app.redis-stream.reclaim.enabled:true}") boolean enabled,
            @Value("${app.redis-stream.reclaim.min-idle-seconds:60}") long minIdleSeconds,
            @Value("${app.redis-stream.reclaim.interval-seconds:30}") long intervalSeconds,
            @Value("${app.redis-stream.reclaim.batch-size:10}") int batchSize,
            @Value("${app.redis-stream.reclaim.max-deliveries:5}") int maxDeliveries,
            @Value("${app.redis-stream.reclaim.processing-timeout-seconds:600}") long processingTimeoutSeconds) {
        setReclaimSettings(new ReclaimSettings(enabled, Duration.ofSeconds(minIdleSeconds),
                Duration.ofSeconds(intervalSeconds), batchSize, maxDeliveries,
                Duration.ofSeconds(processingTimeoutSeconds)));
    }

    /** 测试或手工装配时直接设置回收参数 */
    public void setReclaimSettings(ReclaimSettings settings) {
        this.reclaimSettings = Objects.requireNonNull(settings);
    }

    public ReclaimSettings getReclaimSettings() {
        return reclaimSettings;
    }

    public String getConsumerName() {
        return consumerName;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName() + "-" + consumerName);
            t.setDaemon(true);
            return t;
        });
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName() + "-heartbeat-" + consumerName);
            t.setDaemon(true);
            return t;
        });

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("[Redis Stream 消费] {} 消费者已启动: consumerName={}, reclaim={}",
                taskDisplayName(), consumerName, reclaimSettings);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        log.info("[Redis Stream 消费] {} 消费者已停止: consumerName={}", taskDisplayName(), consumerName);
    }

    /** 等待消费线程退出（测试用） */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return executorService == null || executorService.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
                // 2.1 到点先回收超时未确认的消息（与正常消费在同一线程串行执行）
                reclaimIfDue();

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
                        if (!running.get()) {
                            // 已停止：剩余消息不处理也不 ACK，留在 PEL 里由其它实例回收
                            break;
                        }
                        processRecord(record, 1);
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[Redis Stream 消费] 消费者线程收到中断信号");
                    break;
                }
                if (!running.get()) {
                    break;
                }
                log.error("[Redis Stream 消费] 消费消息异常: error={}", e.getMessage(), e);
                try {
                    Thread.sleep(1000); // 异常时适当休眠避免 CPU 狂转
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ==================== 超时消息回收 ====================

    private void reclaimIfDue() {
        if (!reclaimSettings.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReclaimAt < reclaimSettings.interval().toMillis()) {
            return;
        }
        lastReclaimAt = now;
        try {
            int n = reclaimPendingMessages();
            if (n > 0) {
                log.info("[Redis Stream 回收] {} 本轮回收超时消息 {} 条", taskDisplayName(), n);
            }
        } catch (Exception e) {
            log.error("[Redis Stream 回收] {} 回收超时消息失败: error={}", taskDisplayName(), e.getMessage(), e);
        }
    }

    /**
     * 扫描并回收 idle 超时的 pending 消息。会沿 XAUTOCLAIM 返回的游标一直扫到底（每批 batchSize 条）。
     *
     * @return 本次认领的消息条数
     */
    public int reclaimPendingMessages() {
        int total = 0;
        String cursor = "0-0";
        // 防御：最多扫 100 批，避免异常情况下游标不前进导致死循环
        for (int round = 0; round < 100; round++) {
            ClaimedMessages<byte[], byte[]> claimed = xautoclaim(cursor);
            if (claimed == null) {
                break;
            }
            List<io.lettuce.core.StreamMessage<byte[], byte[]>> messages = claimed.getMessages();
            if (messages != null && !messages.isEmpty()) {
                Map<String, Long> deliveryCounts = deliveryCountsOf(messages);
                for (io.lettuce.core.StreamMessage<byte[], byte[]> m : messages) {
                    if (m == null || m.getBody() == null) {
                        continue; // Redis 6.2 中已被 XTRIM 删除的条目会返回 nil
                    }
                    total++;
                    MapRecord<String, Object, Object> record = toRecord(m);
                    long deliveries = deliveryCounts.getOrDefault(m.getId(), (long) reclaimSettings.maxDeliveries() + 1);
                    log.warn("[Redis Stream 回收] 认领超时未确认消息: stream={}, recordId={}, deliveryCount={}",
                            streamKey(), m.getId(), deliveries);
                    processRecord(record, deliveries);
                }
            }
            cursor = claimed.getId();
            if (cursor == null || "0-0".equals(cursor)) {
                break;
            }
        }
        return total;
    }

    private ClaimedMessages<byte[], byte[]> xautoclaim(String cursor) {
        byte[] key = bytes(streamKey());
        XAutoClaimArgs<byte[]> args = XAutoClaimArgs.Builder
                .xautoclaim(io.lettuce.core.Consumer.from(bytes(groupName()), bytes(consumerName)),
                        reclaimSettings.minIdle(), cursor)
                .count(reclaimSettings.batchSize());
        return withStreamCommands(cmds -> await(cmds.xautoclaim(key, args)));
    }

    /** 批量查询当前消费者名下这些消息的投递次数 */
    private Map<String, Long> deliveryCountsOf(List<io.lettuce.core.StreamMessage<byte[], byte[]>> messages) {
        List<String> ids = messages.stream().filter(Objects::nonNull).map(io.lettuce.core.StreamMessage::getId).toList();
        Map<String, Long> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                streamKey(), Consumer.from(groupName(), consumerName),
                Range.closed(ids.getFirst(), ids.getLast()), ids.size() + 10);
        for (PendingMessage pm : pending) {
            counts.put(pm.getIdAsString(), pm.getTotalDeliveryCount());
        }
        return counts;
    }

    /**
     * 处理期间的心跳：XCLAIM key group self 0 id JUSTID。
     * 把消息 idle 清零、不增加投递次数，告诉其它节点"我还活着，别抢"。
     */
    private void heartbeat(RecordId recordId) {
        try {
            byte[] key = bytes(streamKey());
            withStreamCommands(cmds -> await(cmds.xclaim(key,
                    io.lettuce.core.Consumer.from(bytes(groupName()), bytes(consumerName)),
                    XClaimArgs.Builder.justid(), recordId.getValue())));
        } catch (Exception e) {
            log.debug("[Redis Stream 心跳] 续期失败: recordId={}, error={}", recordId, e.getMessage());
        }
    }

    private ScheduledFuture<?> startHeartbeat(RecordId recordId) {
        if (!reclaimSettings.enabled() || heartbeatScheduler == null || heartbeatScheduler.isShutdown()) {
            return null;
        }
        long periodMs = Math.max(200, reclaimSettings.minIdle().toMillis() / 3);
        long deadline = System.currentTimeMillis() + reclaimSettings.processingTimeout().toMillis();
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() > deadline) {
                // 超过最长处理时间不再续期，允许被回收（防止线程卡死的任务永远占着消息）
                throw new CancellationException("processing timeout");
            }
            heartbeat(recordId);
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private <R> R withStreamCommands(Function<RedisStreamAsyncCommands<byte[], byte[]>, R> fn) {
        return stringRedisTemplate.execute((RedisCallback<R>) (RedisConnection connection) -> {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof RedisStreamAsyncCommands<?, ?> cmds)) {
                throw new IllegalStateException("XAUTOCLAIM 需要 Lettuce 驱动，当前连接: " + nativeConnection.getClass());
            }
            return fn.apply((RedisStreamAsyncCommands<byte[], byte[]>) cmds);
        });
    }

    private static <R> R await(io.lettuce.core.RedisFuture<R> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private MapRecord<String, Object, Object> toRecord(io.lettuce.core.StreamMessage<byte[], byte[]> m) {
        Map<Object, Object> body = new LinkedHashMap<>();
        m.getBody().forEach((k, v) -> body.put(str(k), str(v)));
        return StreamRecords.newRecord().in(streamKey()).withId(RecordId.of(m.getId())).ofMap(body);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    // ==================== 单条消息处理 ====================

    /**
     * @param deliveryCount 该消息的投递次数：正常读取为 1，回收时为 XPENDING 中的实际次数
     */
    private void processRecord(MapRecord<String, Object, Object> record, long deliveryCount) {
        RecordId recordId = record.getId();
        Map<Object, Object> valueMap = record.getValue();

        T payload;
        try {
            payload = parsePayload(valueMap);
        } catch (Exception e) {
            log.error("[Redis Stream 消费] 解析任务载荷失败: recordId={}, error={}", recordId, e.getMessage(), e);
            // 脏数据直接 ACK 移出队列，防止死循环
            ack(recordId);
            return;
        }

        // 投递次数超限：说明这条消息反复导致消费者崩溃（毒消息），不再执行，记为失败并 ACK
        if (deliveryCount > reclaimSettings.maxDeliveries()) {
            String error = "超过最大投递次数(" + reclaimSettings.maxDeliveries() + ")，deliveryCount=" + deliveryCount;
            log.error("[Redis Stream 回收] {}任务{}，记为失败: recordId={}", taskDisplayName(), error, recordId);
            try {
                handleExhaustedRetries(payload, error);
            } catch (Exception e) {
                log.error("[Redis Stream 回收] 标记失败异常: recordId={}, error={}", recordId, e.getMessage(), e);
            }
            ack(recordId);
            return;
        }

        int retryCount = 0;
        Object retryObj = valueMap.get(AsyncTaskStreamConstants.FIELD_RETRY_COUNT);
        if (retryObj != null) {
            try {
                retryCount = Integer.parseInt(String.valueOf(retryObj));
            } catch (NumberFormatException ignored) {}
        }

        ScheduledFuture<?> heartbeat = startHeartbeat(recordId);
        try {
            // 执行实际业务处理（必须幂等）
            handleMessage(payload);

            // 消费成功，向 Redis 发送 ACK
            ack(recordId);
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
            ack(recordId);
        } finally {
            // 无论成功、业务异常，还是 Error（模拟进程崩溃）都停止心跳；
            // 后两种情况下消息未 ACK，idle 开始增长，超过 min-idle 后被回收。
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private void ack(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(streamKey(), groupName(), recordId);
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(Map<Object, Object> data);

    /**
     * 处理一条任务。<b>必须幂等</b>：同一任务可能因为崩溃回收被执行多次。
     */
    protected abstract void handleMessage(T payload) throws Exception;

    protected abstract void retryTask(T payload, int nextRetryCount, String lastError);

    protected abstract void handleExhaustedRetries(T payload, String error);
}
