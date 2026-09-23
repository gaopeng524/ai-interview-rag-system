package com.example.demo.common.ai.trace;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 🚀 AI 全链路 Trace 核心调度器 (TraceManager)
 * <p>
 * 基于 ThreadLocal + SLF4J MDC 实现单线程/跨线程的调用链树状自动化组装与度量。
 */
@Slf4j
public final class TraceManager {

    public static final String MDC_TRACE_KEY = "traceId";

    private static final ThreadLocal<TraceContext> CONTEXT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<TraceSpan> CURRENT_SPAN_HOLDER = new ThreadLocal<>();

    private TraceManager() {}

    /**
     * 开启一段新的 Trace 调用链
     */
    public static TraceContext startTrace(String rootName) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return startTrace(rootName, traceId);
    }

    /**
     * 开启一段新的 Trace 调用链（允许传入已有 traceId，例如从网关或 Header 继承）
     */
    public static TraceContext startTrace(String rootName, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        TraceContext context = new TraceContext(traceId);
        TraceSpan root = new TraceSpan();
        root.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        root.setName(rootName);
        root.setStartTimeMs(System.currentTimeMillis());

        context.setRootSpan(root);
        context.setCurrentSpan(root);

        CONTEXT_HOLDER.set(context);
        CURRENT_SPAN_HOLDER.set(root);

        // 🌟 MDC 日志染色：当前线程后续所有 log.info 将自动带上 traceId 印记
        MDC.put(MDC_TRACE_KEY, traceId);

        return context;
    }

    /**
     * 核心函数式环绕记录：自动计时、自动挂载父子 Span 树、异常感知
     */
    public static <T> T record(String spanName, Supplier<T> action) {
        TraceContext context = CONTEXT_HOLDER.get();
        if (context == null) {
            // 若当前未开启 Trace，直接穿透执行业务逻辑
            return action.get();
        }

        TraceSpan parent = CURRENT_SPAN_HOLDER.get();
        TraceSpan child = new TraceSpan();
        child.setSpanId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        child.setName(spanName);
        child.setParentSpanId(parent != null ? parent.getSpanId() : null);
        child.setStartTimeMs(System.currentTimeMillis());

        if (parent != null) {
            parent.getChildren().add(child);
        }
        CURRENT_SPAN_HOLDER.set(child);

        try {
            T result = action.get();
            child.setStatus("SUCCESS");
            return result;
        } catch (Exception ex) {
            child.setStatus("ERROR");
            child.tag("error.type", ex.getClass().getSimpleName());
            child.tag("error.message", ex.getMessage() != null ? ex.getMessage() : "Unknown error");
            throw ex;
        } finally {
            child.setDurationMs(System.currentTimeMillis() - child.getStartTimeMs());
            CURRENT_SPAN_HOLDER.set(parent);
        }
    }

    /**
     * 针对无返回值的 Runnable 业务逻辑包裹
     */
    public static void recordRunnable(String spanName, Runnable action) {
        record(spanName, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 为当前活跃 Span 动态打上业务属性/指标标签
     */
    public static void tag(String key, Object value) {
        TraceSpan current = CURRENT_SPAN_HOLDER.get();
        if (current != null) {
            current.tag(key, value);
        }
    }

    /**
     * 结束当前 Trace，计算总耗时，清理 ThreadLocal 与 MDC
     */
    public static TraceContext endTrace() {
        TraceContext context = CONTEXT_HOLDER.get();
        if (context != null) {
            long total = System.currentTimeMillis() - context.getStartTimeMs();
            context.setTotalDurationMs(total);
            if (context.getRootSpan() != null) {
                context.getRootSpan().setDurationMs(total);
            }
        }
        CONTEXT_HOLDER.remove();
        CURRENT_SPAN_HOLDER.remove();
        MDC.remove(MDC_TRACE_KEY);
        return context;
    }

    /**
     * 获取当前线程活跃的 TraceContext
     */
    public static TraceContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取当前线程的 TraceId（若无则返回空字符串）
     */
    public static String getTraceId() {
        TraceContext ctx = CONTEXT_HOLDER.get();
        return ctx != null ? ctx.getTraceId() : "";
    }

    /**
     * 跨线程池传播包装：抓取当前线程上下文，无损传递至子线程执行
     */
    public static Runnable wrap(Runnable task) {
        TraceContext context = CONTEXT_HOLDER.get();
        TraceSpan currentSpan = CURRENT_SPAN_HOLDER.get();
        String mdcTrace = MDC.get(MDC_TRACE_KEY);

        return () -> {
            if (context != null) {
                CONTEXT_HOLDER.set(context);
                CURRENT_SPAN_HOLDER.set(currentSpan);
            }
            if (mdcTrace != null) {
                MDC.put(MDC_TRACE_KEY, mdcTrace);
            }
            try {
                task.run();
            } finally {
                CONTEXT_HOLDER.remove();
                CURRENT_SPAN_HOLDER.remove();
                MDC.remove(MDC_TRACE_KEY);
            }
        };
    }

    /**
     * 格式化输出直观优美的 ASCII 链路调用树
     */
    public static String renderAsciiTree(TraceContext context) {
        if (context == null || context.getRootSpan() == null) {
            return "[No Trace Context]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n================ 📊 [AI 全链路 Trace 审计报告] traceId=").append(context.getTraceId()).append(" ================\n");
        TraceSpan root = context.getRootSpan();
        sb.append(root.getName())
          .append(" (总耗时: ").append(root.getDurationMs()).append("ms | 状态: ").append(root.getStatus()).append(")");
        if (!root.getTags().isEmpty()) {
            sb.append(" ").append(formatTags(root.getTags()));
        }
        sb.append("\n");

        renderChildren(sb, root.getChildren(), "");
        sb.append("========================================================================================\n");
        return sb.toString();
    }

    private static void renderChildren(StringBuilder sb, List<TraceSpan> children, String prefix) {
        if (children == null || children.isEmpty()) return;

        for (int i = 0; i < children.size(); i++) {
            TraceSpan child = children.get(i);
            boolean isLast = (i == children.size() - 1);
            String branch = isLast ? "└── " : "├── ";

            sb.append(prefix).append(branch)
              .append(child.getName())
              .append(" [耗时: ").append(child.getDurationMs()).append("ms | 状态: ").append(child.getStatus()).append("]");

            if (!child.getTags().isEmpty()) {
                sb.append(" ").append(formatTags(child.getTags()));
            }
            sb.append("\n");

            String nextPrefix = prefix + (isLast ? "    " : "│   ");
            renderChildren(sb, child.getChildren(), nextPrefix);
        }
    }

    private static String formatTags(Map<String, Object> tags) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : tags.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}