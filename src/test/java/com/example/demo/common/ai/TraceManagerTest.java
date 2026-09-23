package com.example.demo.common.ai;

import com.example.demo.common.ai.trace.TraceContext;
import com.example.demo.common.ai.trace.TraceManager;
import com.example.demo.common.ai.trace.TraceSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("🌲 AI 全链路轻量级 Trace 系统单元测试")
class TraceManagerTest {

    @Test
    @DisplayName("测试：Trace 树状层级关系、耗时与指标 Tags 正确记录")
    void testTraceHierarchyAndTags() {
        TraceContext ctx = TraceManager.startTrace("chat.rag.pipeline");
        assertNotNull(ctx.getTraceId());
        assertEquals(ctx.getTraceId(), MDC.get(TraceManager.MDC_TRACE_KEY), "MDC 中必须正确染色 traceId");

        TraceManager.tag("sessionId", 1001L);

        // 步骤 1：RAG 检索父阶段
        String ragResult = TraceManager.record("ai.rag.retrieve", () -> {
            TraceManager.tag("kbIds", "[5, 6]");

            // 步骤 1.1：向量数据库子阶段
            return TraceManager.record("db.vector.ann_search", () -> {
                TraceManager.tag("hitCount", 3);
                return "chunk1, chunk2";
            });
        });

        assertEquals("chunk1, chunk2", ragResult);

        // 步骤 2：LLM 流式调用
        TraceManager.recordRunnable("ai.llm.stream", () -> {
            TraceManager.tag("model", "mimo-v2.5-pro");
            TraceManager.tag("tokens", 1680);
        });

        // 结束 Trace
        TraceContext completed = TraceManager.endTrace();
        assertNull(MDC.get(TraceManager.MDC_TRACE_KEY), "Trace 结束后 MDC 必须彻底清空防内存泄漏");
        assertNotNull(completed);
        assertTrue(completed.getTotalDurationMs() >= 0);

        TraceSpan root = completed.getRootSpan();
        assertEquals("chat.rag.pipeline", root.getName());
        assertEquals("1001", String.valueOf(root.getTags().get("sessionId")));
        assertEquals(2, root.getChildren().size(), "根节点必须挂载 2 个直接子阶段");

        // 验证 RAG 检索及其子阶段
        TraceSpan ragSpan = root.getChildren().get(0);
        assertEquals("ai.rag.retrieve", ragSpan.getName());
        assertEquals(1, ragSpan.getChildren().size(), "RAG 节点下必须挂载 1 个向量库检索子阶段");
        assertEquals("db.vector.ann_search", ragSpan.getChildren().get(0).getName());

        // 验证 ASCII 树渲染
        String asciiTree = TraceManager.renderAsciiTree(completed);
        assertNotNull(asciiTree);
        assertTrue(asciiTree.contains("chat.rag.pipeline"));
        assertTrue(asciiTree.contains("ai.rag.retrieve"));
        assertTrue(asciiTree.contains("db.vector.ann_search"));
        assertTrue(asciiTree.contains("ai.llm.stream"));
        assertTrue(asciiTree.contains("mimo-v2.5-pro"));

        System.out.println(asciiTree);
    }

    @Test
    @DisplayName("测试：业务异常发生时 Span 自动标红为 ERROR 并捕获异常信息")
    void testTraceExceptionHandling() {
        TraceManager.startTrace("failing.task");

        assertThrows(IllegalStateException.class, () -> {
            TraceManager.record("risky.operation", () -> {
                TraceManager.tag("step", "about-to-fail");
                throw new IllegalStateException("Simulated Network Timeout");
            });
        });

        TraceContext completed = TraceManager.endTrace();
        assertNotNull(completed);
        TraceSpan root = completed.getRootSpan();
        assertEquals(1, root.getChildren().size());

        TraceSpan failedSpan = root.getChildren().get(0);
        assertEquals("ERROR", failedSpan.getStatus());
        assertEquals("IllegalStateException", failedSpan.getTags().get("error.type"));
        assertEquals("Simulated Network Timeout", failedSpan.getTags().get("error.message"));

        String ascii = TraceManager.renderAsciiTree(completed);
        assertTrue(ascii.contains("状态: ERROR"));
        System.out.println(ascii);
    }

    @Test
    @DisplayName("测试：TraceManager.wrap 跨线程池异步透传上下文与 MDC")
    void testCrossThreadContextPropagation() throws InterruptedException {
        TraceContext ctx = TraceManager.startTrace("async.root");
        String originalTraceId = ctx.getTraceId();

        AtomicReference<String> threadTraceId = new AtomicReference<>();
        AtomicReference<String> threadMdc = new AtomicReference<>();

        Thread worker = new Thread(TraceManager.wrap(() -> {
            threadTraceId.set(TraceManager.getTraceId());
            threadMdc.set(MDC.get(TraceManager.MDC_TRACE_KEY));
            TraceManager.recordRunnable("async.child.step", () -> {
                TraceManager.tag("asyncKey", "asyncValue");
            });
        }));

        worker.start();
        worker.join();

        TraceContext completed = TraceManager.endTrace();
        assertEquals(originalTraceId, threadTraceId.get(), "异步子线程中必须继承相同的 traceId");
        assertEquals(originalTraceId, threadMdc.get(), "异步子线程中的 MDC 必须正确注入");
        assertEquals(1, completed.getRootSpan().getChildren().size());
        assertEquals("async.child.step", completed.getRootSpan().getChildren().get(0).getName());
    }
}