package com.example.demo.common.ai.trace;

import lombok.Data;

/**
 * 📦 AI 全链路 Trace 上下文容器
 * <p>
 * 聚合一次请求的全局调用树、唯一 TraceId、时间度量及累计指标。
 */
@Data
public class TraceContext {

    /** 全局唯一调用链 ID */
    private final String traceId;

    /** 根 Span 节点 */
    private TraceSpan rootSpan;

    /** 当前活跃执行的 Span（随调用深入/浮出而改变） */
    private TraceSpan currentSpan;

    /** 请求总开始时间 */
    private final long startTimeMs;

    /** 请求端到端总耗时 */
    private long totalDurationMs;

    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.startTimeMs = System.currentTimeMillis();
    }
}