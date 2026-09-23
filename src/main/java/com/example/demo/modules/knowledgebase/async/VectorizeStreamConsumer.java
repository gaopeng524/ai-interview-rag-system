package com.example.demo.modules.knowledgebase.async;

import com.example.demo.common.async.AbstractStreamConsumer;
import com.example.demo.common.constant.AsyncTaskStreamConstants;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化异步任务消费者
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizeTaskPayload> {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamConsumer(
            StringRedisTemplate stringRedisTemplate,
            @Lazy KnowledgeBaseService knowledgeBaseService,
            KnowledgeBaseMapper knowledgeBaseMapper) {
        super(stringRedisTemplate);
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    protected String taskDisplayName() {
        return "知识库向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP;
    }

    @Override
    protected String consumerPrefix() {
        return "kb-vec-worker";
    }

    @Override
    protected String threadName() {
        return "redis-stream-vectorize";
    }

    @Override
    protected VectorizeTaskPayload parsePayload(Map<Object, Object> data) {
        Object kbIdObj = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        Object contentObj = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);

        if (kbIdObj == null || contentObj == null) {
            throw new IllegalArgumentException("消息缺失必要字段 kb_id 或 content");
        }

        Long kbId = Long.parseLong(String.valueOf(kbIdObj));
        String content = String.valueOf(contentObj);
        return new VectorizeTaskPayload(kbId, content);
    }

    @Override
    protected void handleMessage(VectorizeTaskPayload payload) throws Exception {
        Long kbId = payload.kbId();

        // 幂等与存在性检查：如果知识库已被用户删除，则无需处理
        KnowledgeBase kb = knowledgeBaseMapper.findById(kbId);
        if (kb == null) {
            log.warn("[Redis Stream 消费] 知识库已不存在，跳过向量化: kbId={}", kbId);
            return;
        }

        log.info("[Redis Stream 消费] 开始异步执行知识库切词与向量化: kbId={}", kbId);
        knowledgeBaseService.processVectorizeTask(kbId, payload.content());
    }

    @Override
    protected void retryTask(VectorizeTaskPayload payload, int nextRetryCount, String lastError) {
        try {
            StringRecord record = StreamRecords.string(Map.of(
                    AsyncTaskStreamConstants.FIELD_KB_ID, String.valueOf(payload.kbId()),
                    AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(nextRetryCount),
                    AsyncTaskStreamConstants.FIELD_ERROR, lastError != null ? lastError : ""
            )).withStreamKey(streamKey());

            stringRedisTemplate.opsForStream().add(record);
            log.warn("[Redis Stream 重试] 已重新投递向量化任务: kbId={}, nextRetryCount={}", payload.kbId(), nextRetryCount);
        } catch (Exception e) {
            log.error("[Redis Stream 重试] 重新投递任务失败: kbId={}, error={}", payload.kbId(), e.getMessage(), e);
        }
    }

    @Override
    protected void handleExhaustedRetries(VectorizeTaskPayload payload, String error) {
        log.error("[Redis Stream 终态失败] 向量化任务超过最大重试次数: kbId={}, error={}", payload.kbId(), error);
        try {
            knowledgeBaseMapper.updateVectorStatus(payload.kbId(), "FAILED", error, 0);
        } catch (Exception e) {
            log.error("[Redis Stream 终态失败] 更新知识库状态失败: kbId={}, error={}", payload.kbId(), e.getMessage());
        }
    }
}
