package com.example.demo.common.ai.trace;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 🌲 AI 全链路 Trace 节点（Span）
 * <p>
 * 对应调用链树中的一个特定业务执行阶段，包含耗时、状态、上下文指标与子节点集合。
 */
@Data
public class TraceSpan {

    /** 节点唯一标识（8位随机Hex） */
    private String spanId;

    /** 父节点标识（若为根节点则为 null） */
    private String parentSpanId;

    /** 阶段/操作名称（建议使用低基数动宾命名，如 ai.rag.retrieve） */
    private String name;

    /** 开始时间戳（毫秒） */
    private long startTimeMs;

    /** 总耗时（毫秒） */
    private long durationMs;

    /** 状态：SUCCESS / ERROR / TIMEOUT */
    private String status = "SUCCESS";

    /** 结构化指标标签字典（如模型名、Token数、候选切片数） */
    private Map<String, Object> tags = new LinkedHashMap<>();

    /** 子节点列表（构建调用树） */
    private List<TraceSpan> children = new CopyOnWriteArrayList<>();

    public TraceSpan tag(String key, Object value) {
        if (key != null && value != null) {
            this.tags.put(key, value);
        }
        return this;
    }
}