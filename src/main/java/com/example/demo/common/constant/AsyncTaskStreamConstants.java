package com.example.demo.common.constant;

/**
 * 异步任务 Redis Stream 相关常量定义
 */
public final class AsyncTaskStreamConstants {

    private AsyncTaskStreamConstants() {}

    /** 知识库向量化任务 Stream 队列 Key */
    public static final String KB_VECTORIZE_STREAM_KEY = "stream:kb:vectorize";

    /** 知识库向量化消费者组名称 */
    public static final String KB_VECTORIZE_GROUP = "kb_vectorize_group";

    /** 面试评估任务 Stream 队列 Key */
    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "stream:interview:evaluate";

    /** 面试评估任务消费者组名称 */
    public static final String INTERVIEW_EVALUATE_GROUP = "interview_evaluate_group";

    /** 面试评估分布式排重锁 Key 前缀（TTL: 5分钟） */
    public static final String LOCK_INTERVIEW_EVALUATE_PREFIX = "lock:interview:evaluate:";

    /** 知识库考题提炼分布式排重锁 Key 前缀（TTL: 5分钟） */
    public static final String LOCK_KB_QUESTION_GEN_PREFIX = "lock:kb:question:gen:";

    /** 队列最大长度（防止消息积压撑爆内存） */
    public static final long STREAM_MAX_LEN = 1000;

    /** 字段：知识库 ID */
    public static final String FIELD_KB_ID = "kb_id";

    /** 字段：面试会话 ID */
    public static final String FIELD_SESSION_ID = "session_id";

    /** 字段：用户 ID */
    public static final String FIELD_USER_ID = "user_id";

    /** 字段：待向量化文本内容 */
    public static final String FIELD_CONTENT = "content";

    /** 字段：重试次数 */
    public static final String FIELD_RETRY_COUNT = "retry_count";

    /** 字段：错误信息 */
    public static final String FIELD_ERROR = "error";

    /** 最大重试次数 */
    public static final int MAX_RETRY_COUNT = 3;

    /** 消费批次大小 */
    public static final int BATCH_SIZE = 5;

    /** 拉取阻塞等待超时（毫秒） */
    public static final long POLL_TIMEOUT_MS = 2000;
}
