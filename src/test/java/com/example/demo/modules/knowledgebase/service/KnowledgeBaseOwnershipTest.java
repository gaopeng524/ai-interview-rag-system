package com.example.demo.modules.knowledgebase.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseQuestionMapper;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 知识库用户隔离：未登录 / 访问他人知识库 / 访问自己的知识库，以及检索范围限定。
 */
@DisplayName("KnowledgeBaseService 归属校验与检索范围")
class KnowledgeBaseOwnershipTest {

    private static final long OWNER = 100L;
    private static final long OTHER = 200L;
    private static final long KB_ID = 1L;

    private VectorStore vectorStore;
    private KnowledgeBaseMapper mapper;
    private KnowledgeBaseQuestionMapper questionMapper;
    private KnowledgeBaseSearchService searchService;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        mapper = mock(KnowledgeBaseMapper.class);
        questionMapper = mock(KnowledgeBaseQuestionMapper.class);
        searchService = mock(KnowledgeBaseSearchService.class);
        service = new KnowledgeBaseService(vectorStore, null, mapper, null, searchService, null, null, questionMapper);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setUserId(OWNER);
        when(mapper.findById(KB_ID)).thenReturn(kb);
        when(mapper.findIdsByUser(OWNER)).thenReturn(List.of(KB_ID, 2L));
        when(mapper.findIdsByUser(OTHER)).thenReturn(List.of(9L));
    }

    private static void assertCode(ErrorCode expected, Executable call) {
        BusinessException ex = assertThrows(BusinessException.class, call);
        assertEquals(expected.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未登录（userId 为空）：所有入口 403，且不查库")
    void anonymousIsForbidden() {
        assertCode(ErrorCode.FORBIDDEN, () -> service.findAll(null));
        assertCode(ErrorCode.FORBIDDEN, () -> service.findById(KB_ID, null));
        assertCode(ErrorCode.FORBIDDEN, () -> service.deleteById(KB_ID, null));
        assertCode(ErrorCode.FORBIDDEN, () -> service.getStatistics(null));
        assertCode(ErrorCode.FORBIDDEN, () -> service.search("q", List.of(KB_ID), 4, null));
        assertCode(ErrorCode.FORBIDDEN, () -> service.addKnowledge(new KnowledgeBaseDTO.AddRequest("n", "c", "x"), null));
        verifyNoInteractions(mapper, vectorStore, searchService);
    }

    @Test
    @DisplayName("访问他人知识库：查看、删除、检索都是 403，不产生任何删除")
    void otherUserIsForbidden() {
        assertCode(ErrorCode.FORBIDDEN, () -> service.findById(KB_ID, OTHER));
        assertCode(ErrorCode.FORBIDDEN, () -> service.deleteById(KB_ID, OTHER));
        assertCode(ErrorCode.FORBIDDEN, () -> service.search("q", List.of(KB_ID), 4, OTHER));
        assertCode(ErrorCode.FORBIDDEN, () -> service.searchSemantic(
                new KnowledgeBaseDTO.SearchRequest("q", List.of(9L, KB_ID), 4), OTHER));
        verify(mapper, never()).deleteById(anyLong());
        verify(questionMapper, never()).deleteByKnowledgeBaseId(anyLong());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("没有归属人的历史知识库对任何用户都不可见")
    void orphanKnowledgeBaseIsForbidden() {
        KnowledgeBase orphan = new KnowledgeBase();
        orphan.setId(5L);
        when(mapper.findById(5L)).thenReturn(orphan);
        assertCode(ErrorCode.FORBIDDEN, () -> service.findById(5L, OWNER));
    }

    @Test
    @DisplayName("不存在的知识库返回 404")
    void notFound() {
        assertCode(ErrorCode.NOT_FOUND, () -> service.findById(404L, OWNER));
    }

    @Test
    @DisplayName("访问自己的知识库：可查看；删除时同时清理考题和向量切片")
    void ownerCanReadAndDelete() {
        assertEquals(KB_ID, service.findById(KB_ID, OWNER).getId());

        service.deleteById(KB_ID, OWNER);
        verify(questionMapper).deleteByKnowledgeBaseId(KB_ID);
        verify(mapper).deleteById(KB_ID);
        verify(vectorStore).delete(argThat((Filter.Expression e) -> e.toString().contains("kb_id") && e.toString().contains("1")));
        verify(searchService).evictRagCache();
    }

    @Test
    @DisplayName("列表、分类、统计只查当前用户名下的数据")
    void listingIsScopedToUser() {
        service.findAll(OWNER);
        service.getAllCategories(OWNER);
        service.getStatistics(OWNER);
        service.searchByName("java", OWNER);
        verify(mapper).findAllByUser(OWNER);
        verify(mapper).findAllCategories(OWNER);
        verify(mapper).countAll(OWNER);
        verify(mapper).searchByName(OWNER, "java");
    }

    @Test
    @DisplayName("不指定知识库的检索：限定为自己名下的全部知识库，而不是全局检索")
    void unscopedSearchIsLimitedToOwnKnowledgeBases() {
        when(searchService.search("q", List.of(KB_ID, 2L), 4)).thenReturn(List.of(new Document("hit")));
        assertEquals(1, service.search("q", null, 4, OWNER).size());
        verify(searchService).search("q", List.of(KB_ID, 2L), 4);
        verify(searchService, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("用户没有任何知识库时：直接返回空，绝不落到全局检索")
    void userWithoutKnowledgeBasesGetsEmptyResult() {
        when(mapper.findIdsByUser(300L)).thenReturn(List.of());
        assertTrue(service.search("q", null, 4, 300L).isEmpty());
        assertTrue(service.searchSemantic(new KnowledgeBaseDTO.SearchRequest("q", null, 4), 300L).isEmpty());
        assertNotNull(service.queryKnowledgeBase(new KnowledgeBaseDTO.QueryRequest(null, "q"), 300L).answer());
        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("会话里保存的知识库关联：已删除或不属于自己的静默丢弃")
    void filterOwnedDropsForeignAndDeletedIds() {
        assertEquals(List.of(KB_ID), service.filterOwned(List.of(KB_ID, 9L, 777L), OWNER));
        assertTrue(service.filterOwned(List.of(KB_ID), null).isEmpty());
    }

    @Test
    @DisplayName("新建知识库写入归属用户；文件去重只在自己名下进行")
    void createSetsOwner() {
        com.example.demo.modules.knowledgebase.validator.IngestionValidator validator =
                mock(com.example.demo.modules.knowledgebase.validator.IngestionValidator.class);
        com.example.demo.modules.knowledgebase.async.VectorizeStreamProducer producer =
                mock(com.example.demo.modules.knowledgebase.async.VectorizeStreamProducer.class);
        KnowledgeBaseService svc = new KnowledgeBaseService(vectorStore, null, mapper, producer, searchService,
                validator, null, questionMapper);

        svc.addKnowledge(new KnowledgeBaseDTO.AddRequest("笔记", "内容", "分类"), OWNER);
        verify(mapper).insert(argThat((KnowledgeBase kb) -> Long.valueOf(OWNER).equals(kb.getUserId())));
    }
}
