package com.example.demo.modules.knowledgebase.async;

import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseSearchService;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import com.example.demo.modules.rag.chunker.ParentChildChunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 知识库向量化任务的幂等性：同一个 kbId 重复执行不会产生重复向量。
 */
@DisplayName("向量化任务幂等")
class VectorizeIdempotencyTest {

    @Test
    @DisplayName("每次执行都先按 kb_id 删除旧切片，再写入新切片")
    void deletesExistingVectorsBeforeInsert() {
        VectorStore vectorStore = mock(VectorStore.class);
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        ParentChildChunker chunker = mock(ParentChildChunker.class);
        when(chunker.chunkToParentChildDocuments(anyString(), any(), any(), eq(5L)))
                .thenReturn(List.of(new Document("a", Map.of("kb_id", "5")), new Document("b", Map.of("kb_id", "5"))));
        KnowledgeBaseService service = new KnowledgeBaseService(vectorStore, null, mapper, null,
                mock(KnowledgeBaseSearchService.class), null, chunker);

        // 第一次执行（模拟崩溃前）与第二次执行（回收重放）
        service.processVectorizeTask(5L, "content");
        service.processVectorizeTask(5L, "content");

        InOrder order = inOrder(vectorStore);
        for (int i = 0; i < 2; i++) {
            order.verify(vectorStore).delete(argThat((Filter.Expression e) -> e.toString().contains("kb_id") && e.toString().contains("5")));
            order.verify(vectorStore).accept(argThat(docs -> docs.size() == 2));
        }
        verify(mapper, times(2)).updateVectorStatus(5L, "COMPLETED", null, 2);
    }

    @Test
    @DisplayName("已完成的知识库再次收到任务时直接跳过")
    void consumerSkipsCompletedKnowledgeBase() throws Exception {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(5L);
        kb.setVectorStatus("COMPLETED");
        when(mapper.findById(5L)).thenReturn(kb);

        VectorizeStreamConsumer consumer = new VectorizeStreamConsumer(mock(StringRedisTemplate.class), service, mapper);
        Method handle = VectorizeStreamConsumer.class.getDeclaredMethod("handleMessage", VectorizeStreamConsumer.VectorizeTaskPayload.class);
        handle.setAccessible(true);
        handle.invoke(consumer, new VectorizeStreamConsumer.VectorizeTaskPayload(5L, "content"));

        verifyNoInteractions(service);
        assertEquals("COMPLETED", kb.getVectorStatus());
    }
}
