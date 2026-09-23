package com.example.demo.modules.knowledgebase.async;

import com.example.demo.common.async.AbstractStreamProducer;
import com.example.demo.common.constant.AsyncTaskStreamConstants;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化异步任务生产者
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamProducer(StringRedisTemplate stringRedisTemplate, KnowledgeBaseMapper knowledgeBaseMapper) {
        super(stringRedisTemplate);
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    /**
     * 发送知识库向量化任务
     */
    public boolean sendVectorizeTask(Long kbId, String content) {
        return sendTask(new VectorizeTaskPayload(kbId, content));
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
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, String.valueOf(payload.kbId()),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        // 生产失败时，直接将数据库状态标记为 FAILED
        try {
            knowledgeBaseMapper.updateVectorStatus(payload.kbId(), "FAILED", error, 0);
        } catch (Exception e) {
            log.error("[Redis Stream 生产] 更新失败状态异常: kbId={}, error={}", payload.kbId(), e.getMessage());
        }
    }
}
