package com.example.demo.modules.knowledgebase.controller;

import com.example.demo.common.config.SecurityConfig;
import com.example.demo.common.exception.GlobalExceptionHandler;
import com.example.demo.common.filter.JwtAuthenticationFilter;
import com.example.demo.common.security.JwtService;
import com.example.demo.modules.interview.service.InterviewService;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseQuestionMapper;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBaseQuestionEntity;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseQuestionService;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseSearchService;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库与考题接口的 HTTP 层鉴权：真实的 SecurityConfig + JWT 过滤器 + 两个 Controller + KnowledgeBaseService，
 * 只把 Mapper、向量库换成 mock。覆盖未登录 / 访问他人知识库 / 访问自己的知识库。
 */
@SpringJUnitWebConfig(KnowledgeBaseAccessTest.TestConfig.class)
@DisplayName("知识库接口：按用户隔离")
class KnowledgeBaseAccessTest {

    private static final long OWNER = 100L;
    private static final long OTHER = 200L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
            KnowledgeBaseController.class, KnowledgeBaseInterviewController.class})
    static class TestConfig {
        @Bean JwtService jwtService() {
            return new JwtService("unit-test-secret-key-unit-test-secret-key-0123456789", 3_600_000L);
        }
        @Bean KnowledgeBaseMapper knowledgeBaseMapper() { return mock(KnowledgeBaseMapper.class); }
        @Bean KnowledgeBaseQuestionMapper questionMapper() { return mock(KnowledgeBaseQuestionMapper.class); }
        @Bean VectorStore vectorStore() { return mock(VectorStore.class); }
        @Bean KnowledgeBaseSearchService searchService() { return mock(KnowledgeBaseSearchService.class); }
        @Bean InterviewService interviewService() { return mock(InterviewService.class); }
        @Bean KnowledgeBaseQuestionService questionService() { return mock(KnowledgeBaseQuestionService.class); }
        @Bean KnowledgeBaseService knowledgeBaseService(VectorStore vs, KnowledgeBaseMapper m,
                                                        KnowledgeBaseSearchService ss, KnowledgeBaseQuestionMapper qm) {
            return new KnowledgeBaseService(vs, null, m, null, ss, null, null, qm);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtService jwtService;
    @Autowired KnowledgeBaseMapper mapper;
    @Autowired KnowledgeBaseQuestionMapper questionMapper;
    @Autowired KnowledgeBaseQuestionService questionService;
    @Autowired KnowledgeBaseSearchService searchService;
    @Autowired VectorStore vectorStore;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(mapper, questionMapper, questionService, searchService, vectorStore);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setUserId(OWNER);
        when(mapper.findById(1L)).thenReturn(kb);
        when(mapper.findIdsByUser(OWNER)).thenReturn(List.of(1L));
        when(mapper.findIdsByUser(OTHER)).thenReturn(List.of());
        when(mapper.findAllByUser(anyLong())).thenReturn(List.of());
        // 考题 7 属于知识库 1
        when(questionService.knowledgeBaseIdOf(7L)).thenReturn(1L);
        when(questionService.listQuestions(anyLong(), any(), any(), any(), any())).thenReturn(List.of());
    }

    private String bearer(long userId) {
        return "Bearer " + jwtService.generateToken("u" + userId, userId);
    }

    @Test
    @DisplayName("未登录 → 401")
    void anonymous() throws Exception {
        mvc.perform(get("/api/knowledgebase/list")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/knowledgebase/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/knowledgebase/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/knowledgebase/1/questions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/knowledgebase/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper, questionService, searchService);
    }

    @Test
    @DisplayName("访问他人知识库及其考题 → 403，不产生删除，也不触达业务层")
    void otherUser() throws Exception {
        String auth = bearer(OTHER);
        mvc.perform(get("/api/knowledgebase/1").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/knowledgebase/1").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledgebase/1/questions").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledgebase/1/questions/categories").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledgebase/1/questions/generate").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledgebase/1/interview-capacity").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/knowledgebase/questions/7").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(put("/api/knowledgebase/questions/7/status").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isForbidden());
        // 指定别人的知识库做语义检索
        mvc.perform(post("/api/knowledgebase/search").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\",\"knowledgeBaseIds\":[1]}"))
                .andExpect(status().isForbidden());

        verify(mapper, never()).deleteById(anyLong());
        verify(questionService, never()).deleteQuestion(anyLong());
        verify(questionService, never()).listQuestions(anyLong(), any(), any(), any(), any());
        verify(questionService, never()).submitGenerationTask(anyLong(), any());
        verifyNoInteractions(searchService, vectorStore);
    }

    @Test
    @DisplayName("访问自己的知识库 → 200；列表只查自己名下")
    void owner() throws Exception {
        String auth = bearer(OWNER);
        mvc.perform(get("/api/knowledgebase/list").header("Authorization", auth)).andExpect(status().isOk());
        verify(mapper).findAllByUser(OWNER);
        mvc.perform(get("/api/knowledgebase/1").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/api/knowledgebase/1/questions").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(delete("/api/knowledgebase/questions/7").header("Authorization", auth)).andExpect(status().isOk());
        verify(questionService).deleteQuestion(7L);
        mvc.perform(delete("/api/knowledgebase/1").header("Authorization", auth)).andExpect(status().isOk());
        verify(mapper).deleteById(1L);
        verify(questionMapper).deleteByKnowledgeBaseId(1L);
    }

    @Test
    @DisplayName("没有知识库的用户不指定范围检索 → 200 空结果，不做全局检索")
    void userWithoutKbSearchesNothing() throws Exception {
        mvc.perform(post("/api/knowledgebase/search").header("Authorization", bearer(OTHER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"q\"}"))
                .andExpect(status().isOk());
        verifyNoInteractions(searchService, vectorStore);
    }
}
