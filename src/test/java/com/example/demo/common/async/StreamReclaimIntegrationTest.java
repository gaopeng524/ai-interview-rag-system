package com.example.demo.common.async;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AbstractStreamConsumer 超时消息回收的集成测试（真实 Redis）。
 */
@DisplayName("Redis Stream 超时未确认消息回收（集成测试）")
class StreamReclaimIntegrationTest {

    private static RedisTestSupport redis;
    private static StringRedisTemplate template;

    private final List<TestConsumer> started = new ArrayList<>();
    private String stream;

    @BeforeAll
    static void startRedis() {
        redis = RedisTestSupport.start();
        template = new StringRedisTemplate(redis.connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void newStream() {
        stream = "test:stream:" + UUID.randomUUID();
        // 先建好消费组（从 0 开始），避免消费者线程异步建组（从 $ 开始）与发布消息之间的竞态
        template.opsForStream().createGroup(stream, ReadOffset.from("0"), "test-group");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        for (TestConsumer c : started) {
            c.shutdown();
            c.awaitTermination(Duration.ofSeconds(5));
        }
        template.delete(stream);
    }

    // ------------------------------------------------------------------

    /** 模拟进程崩溃：Error 不会被业务 catch(Exception) 吃掉，消费线程直接死亡，消息不 ACK */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated crash");
        }
    }

    /**
     * 测试用"业务表"：taskId -> 已写入的行。handleMessage 按 taskId 先删后写，是幂等的。
     */
    static final Map<String, List<String>> TABLE = new ConcurrentHashMap<>();

    record Task(String taskId, int rows) {}

    static class TestConsumer extends AbstractStreamConsumer<Task> {
        private final String stream;
        volatile int crashAfterRows = -1;       // >=0：写到第几行时崩溃
        volatile long sleepMs = 0;              // 模拟慢任务
        volatile boolean alwaysCrash = false;
        final AtomicInteger handleCalls = new AtomicInteger();
        final List<String> exhausted = new CopyOnWriteArrayList<>();
        volatile boolean crashed = false;

        TestConsumer(StringRedisTemplate t, String stream, AbstractStreamConsumer.ReclaimSettings settings) {
            super(t);
            this.stream = stream;
            setReclaimSettings(settings);
        }

        @Override protected String taskDisplayName() { return "测试任务"; }
        @Override protected String streamKey() { return stream; }
        @Override protected String groupName() { return "test-group"; }
        @Override protected String consumerPrefix() { return "test-worker"; }
        @Override protected String threadName() { return "test-stream"; }

        @Override
        protected Task parsePayload(Map<Object, Object> data) {
            return new Task(String.valueOf(data.get("task_id")), Integer.parseInt(String.valueOf(data.get("rows"))));
        }

        @Override
        protected void handleMessage(Task task) throws Exception {
            handleCalls.incrementAndGet();
            if (alwaysCrash) {
                crashed = true;
                throw new SimulatedCrash();
            }
            // 幂等写入：先删掉这个任务之前（可能写了一半）的数据，再整体重写
            List<String> rows = Collections.synchronizedList(new ArrayList<>());
            TABLE.put(task.taskId(), rows);
            for (int i = 0; i < task.rows(); i++) {
                if (i == crashAfterRows) {
                    crashed = true;
                    throw new SimulatedCrash();
                }
                rows.add(task.taskId() + "#" + i);
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs / task.rows());
                }
            }
        }

        @Override
        protected void retryTask(Task payload, int nextRetryCount, String lastError) {
            fail("本测试不应走业务重试分支: " + lastError);
        }

        @Override
        protected void handleExhaustedRetries(Task payload, String error) {
            exhausted.add(payload.taskId() + ":" + error);
        }
    }

    private TestConsumer consumer(ReclaimSettingsBuilder b) {
        TestConsumer c = new TestConsumer(template, stream, b.build());
        started.add(c);
        return c;
    }

    private static final class ReclaimSettingsBuilder {
        Duration minIdle = Duration.ofSeconds(1);
        Duration interval = Duration.ofMillis(300);
        int maxDeliveries = 3;

        AbstractStreamConsumer.ReclaimSettings build() {
            return new AbstractStreamConsumer.ReclaimSettings(true, minIdle, interval, 10, maxDeliveries, Duration.ofMinutes(1));
        }
    }

    private String publish(String taskId, int rows) {
        return template.opsForStream().add(StreamRecords.string(Map.of("task_id", taskId, "rows", String.valueOf(rows)))
                .withStreamKey(stream)).getValue();
    }

    private long pendingCount() {
        try {
            PendingMessagesSummary s = template.opsForStream().pending(stream, "test-group");
            return s == null ? 0 : s.getTotalPendingMessages();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void await(String what, Duration timeout, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("等待超时: " + what);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("消费者处理到一半崩溃 → 消息留在 PEL → 新消费者 XAUTOCLAIM 认领并完成，数据不重复")
    void crashedMessageIsReclaimedAndCompletedExactlyOnce() throws Exception {
        String taskId = "task-" + UUID.randomUUID();

        // 1. 消费者 A：写到第 2 行时"进程崩溃"
        TestConsumer a = consumer(new ReclaimSettingsBuilder());
        a.crashAfterRows = 2;
        a.init();
        publish(taskId, 5);

        await("A 崩溃", Duration.ofSeconds(10), () -> a.crashed);
        assertEquals(List.of(taskId + "#0", taskId + "#1"), TABLE.get(taskId), "崩溃前只写了一半");
        await("消息停留在 PEL", Duration.ofSeconds(5), () -> pendingCount() == 1);

        // 2. 模拟重启：新实例（新的随机消费者名），只能通过回收拿到旧消息
        TestConsumer b = consumer(new ReclaimSettingsBuilder());
        b.init();

        await("B 回收并 ACK", Duration.ofSeconds(15), () -> pendingCount() == 0);
        assertEquals(1, b.handleCalls.get(), "B 恰好处理一次");
        assertEquals(List.of(taskId + "#0", taskId + "#1", taskId + "#2", taskId + "#3", taskId + "#4"),
                TABLE.get(taskId), "重放后数据完整且没有重复行");
        assertTrue(b.exhausted.isEmpty());
    }

    @Test
    @DisplayName("超过最大投递次数的消息记为失败并 ACK，不再执行业务")
    void poisonMessageIsMarkedFailedAfterMaxDeliveries() throws Exception {
        String taskId = "poison-" + UUID.randomUUID();
        String id = publish(taskId, 1);

        // 一个已经"死掉"的幽灵消费者：读到消息后被反复认领（每次 XCLAIM 投递次数 +1），一直没 ACK
        Consumer ghost = Consumer.from("test-group", "ghost");
        template.opsForStream().read(ghost, StreamReadOptions.empty().count(1), StreamOffset.create(stream, ReadOffset.lastConsumed()));
        for (int i = 0; i < 3; i++) {
            template.opsForStream().claim(stream, "test-group", "ghost", Duration.ZERO, RecordId.of(id));
        }
        Thread.sleep(1200); // idle 超过 min-idle

        TestConsumer c = consumer(new ReclaimSettingsBuilder()); // maxDeliveries = 3
        c.init();

        await("毒消息被 ACK", Duration.ofSeconds(10), () -> pendingCount() == 0);
        assertEquals(0, c.handleCalls.get(), "超限消息不应再执行业务");
        assertEquals(1, c.exhausted.size());
        assertTrue(c.exhausted.getFirst().startsWith(taskId + ":超过最大投递次数"));
    }

    @Test
    @DisplayName("慢任务（处理时间 > min-idle）有心跳续期，不会被其它节点抢走重复执行")
    void slowButAliveTaskIsNotStolen() throws Exception {
        String taskId = "slow-" + UUID.randomUUID();

        TestConsumer slow = consumer(new ReclaimSettingsBuilder());
        slow.sleepMs = 3000; // 3 秒，远大于 min-idle = 1 秒
        slow.init();
        publish(taskId, 3);
        await("慢任务开始", Duration.ofSeconds(5), () -> slow.handleCalls.get() == 1);

        // 另一个节点每 300ms 扫描一次回收
        TestConsumer other = consumer(new ReclaimSettingsBuilder());
        other.init();

        await("慢任务完成", Duration.ofSeconds(15), () -> pendingCount() == 0);
        Thread.sleep(1500);
        assertEquals(1, slow.handleCalls.get());
        assertEquals(0, other.handleCalls.get(), "存活中的慢任务不应被回收");
        assertEquals(3, TABLE.get(taskId).size());
    }

    @Test
    @DisplayName("反复导致崩溃的消息：每次重启回收一次，达到上限后记为失败")
    void repeatedlyCrashingMessageEventuallyFails() throws Exception {
        String taskId = "crashy-" + UUID.randomUUID();
        publish(taskId, 1);

        // 每一轮模拟一次"进程重启"：新实例（首轮正常读取，之后靠回收）→ 再崩溃，直到被判定为失败
        for (int round = 0; round < 6; round++) {
            TestConsumer c = consumer(new ReclaimSettingsBuilder());
            c.alwaysCrash = true;
            c.init();
            await("本轮结束", Duration.ofSeconds(10), () -> c.crashed || !c.exhausted.isEmpty());
            if (!c.exhausted.isEmpty()) {
                break;
            }
            Thread.sleep(1200); // 等 idle 超过 min-idle
        }
        await("最终 ACK", Duration.ofSeconds(10), () -> pendingCount() == 0);
        int handled = started.stream().mapToInt(c -> c.handleCalls.get()).sum();
        assertEquals(3, handled, "maxDeliveries=3：最多执行 3 次业务");
        long exhausted = started.stream().mapToLong(c -> c.exhausted.size()).sum();
        assertEquals(1, exhausted, "第 4 次投递时记为失败");
    }
}
