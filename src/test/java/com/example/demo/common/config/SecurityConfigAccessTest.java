package com.example.demo.common.config;

import com.example.demo.common.exception.GlobalExceptionHandler;
import com.example.demo.common.filter.JwtAuthenticationFilter;
import com.example.demo.common.security.JwtService;
import com.example.demo.common.util.PdfExportService;
import com.example.demo.modules.interview.controller.VoiceInterviewController;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import com.example.demo.modules.resume.controller.ResumeController;
import com.example.demo.modules.resume.mapper.ResumeMapper;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import com.example.demo.modules.resume.service.DocumentParseService;
import com.example.demo.modules.resume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层鉴权回归：用真实的 SecurityConfig + JwtAuthenticationFilter + ResumeController/ResumeService，
 * 只把 Mapper 换成 mock，覆盖「未登录 / 访问他人简历 / 访问自己简历」三种情况。
 */
@SpringJUnitWebConfig(SecurityConfigAccessTest.TestConfig.class)
@DisplayName("SecurityConfig：简历与语音面试接口必须登录")
class SecurityConfigAccessTest {

    private static final long OWNER_ID = 100L;
    private static final long OTHER_ID = 200L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
            ResumeController.class, VoiceInterviewController.class})
    static class TestConfig {
        @Bean
        JwtService jwtService() {
            return new JwtService("unit-test-secret-key-unit-test-secret-key-0123456789", 3_600_000L);
        }

        @Bean
        ResumeMapper resumeMapper() {
            return mock(ResumeMapper.class);
        }

        @Bean
        InterviewMapper interviewMapper() {
            return mock(InterviewMapper.class);
        }

        @Bean
        ResumeService resumeService(ResumeMapper resumeMapper, InterviewMapper interviewMapper) {
            return new ResumeService(resumeMapper, mock(DocumentParseService.class), null,
                    new ObjectMapper(), mock(PdfExportService.class), interviewMapper, null, null);
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JwtService jwtService;
    @Autowired
    ResumeMapper resumeMapper;
    @Autowired
    InterviewMapper interviewMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(resumeMapper, interviewMapper);
        ResumeEntity resume = new ResumeEntity();
        resume.setId(1L);
        resume.setUserId(OWNER_ID);
        when(resumeMapper.findResumeById(1L)).thenReturn(resume);
        when(resumeMapper.findAnalysesByResumeId(1L)).thenReturn(List.of());
        when(interviewMapper.findSessionsByResumeId(1L)).thenReturn(List.of());
    }

    private String bearer(long userId) {
        return "Bearer " + jwtService.generateToken("user" + userId, userId);
    }

    @Test
    @DisplayName("未登录访问简历/语音面试接口 → 401")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/resumes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/resumes/1/detail")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/resumes/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/voice-interview/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/voice-interview/sessions/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(resumeMapper);
    }

    @Test
    @DisplayName("伪造/过期 token → 401")
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(get("/api/resumes/1/detail").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录用户访问他人简历 → 403，且不产生任何写操作")
    void otherUserIsForbidden() throws Exception {
        mvc.perform(get("/api/resumes/1/detail").header("Authorization", bearer(OTHER_ID)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/resumes/1").header("Authorization", bearer(OTHER_ID)))
                .andExpect(status().isForbidden());
        verify(resumeMapper, never()).deleteResumeById(anyLong(), anyLong());
        verify(resumeMapper, never()).incrementAccessCount(anyLong());
    }

    @Test
    @DisplayName("登录用户访问自己的简历 → 200")
    void ownerIsAllowed() throws Exception {
        mvc.perform(get("/api/resumes/1/detail").header("Authorization", bearer(OWNER_ID)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/voice-interview/sessions").header("Authorization", bearer(OWNER_ID)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/resumes/1").header("Authorization", bearer(OWNER_ID)))
                .andExpect(status().isOk());
        verify(resumeMapper).deleteResumeById(1L, OWNER_ID);
    }

    @Test
    @DisplayName("面试技能目录仍然公开")
    void skillCatalogStaysPublic() throws Exception {
        // 测试上下文没注册 InterviewController，只要不是 401 就说明请求穿过了安全链
        int code = mvc.perform(get("/api/interview/skills")).andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(401, code);
    }
}
