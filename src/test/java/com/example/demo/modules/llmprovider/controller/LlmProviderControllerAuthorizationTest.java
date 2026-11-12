package com.example.demo.modules.llmprovider.controller;

import com.example.demo.common.config.SecurityConfig;
import com.example.demo.common.exception.GlobalExceptionHandler;
import com.example.demo.common.filter.JwtAuthenticationFilter;
import com.example.demo.common.security.JwtService;
import com.example.demo.common.security.Role;
import com.example.demo.modules.llmprovider.service.LlmProviderConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LlmProviderController 角色鉴权：普通用户调用写接口 403，管理员可以调用；查询接口登录即可。
 * 使用真实的 SecurityConfig（含 @EnableMethodSecurity）+ JWT 过滤器，只 mock 业务 Service。
 */
@SpringJUnitWebConfig(LlmProviderControllerAuthorizationTest.TestConfig.class)
@DisplayName("LlmProviderController 管理员鉴权")
class LlmProviderControllerAuthorizationTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
            LlmProviderController.class})
    static class TestConfig {
        @Bean
        JwtService jwtService() {
            return new JwtService("unit-test-secret-key-unit-test-secret-key-0123456789", 3_600_000L);
        }

        @Bean
        LlmProviderConfigService llmProviderConfigService() {
            return mock(LlmProviderConfigService.class);
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JwtService jwtService;
    @Autowired
    LlmProviderConfigService configService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(configService);
        when(configService.listProviders()).thenReturn(List.of());
    }

    /** 所有只允许 ADMIN 的写接口 */
    static Stream<Arguments> adminOnlyEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/llm-provider", "{\"id\":\"p1\"}"),
                Arguments.of(HttpMethod.PUT, "/api/llm-provider/p1", "{\"model\":\"m\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/llm-provider/p1", null),
                Arguments.of(HttpMethod.POST, "/api/llm-provider/reload", null),
                Arguments.of(HttpMethod.PUT, "/api/llm-provider/default-provider", "{\"defaultProvider\":\"p1\"}"),
                Arguments.of(HttpMethod.PUT, "/api/llm-provider/default-embedding-provider", "{\"defaultEmbeddingProvider\":\"p1\"}"),
                Arguments.of(HttpMethod.PUT, "/api/llm-provider/voice/asr", "{}"),
                Arguments.of(HttpMethod.PUT, "/api/llm-provider/voice/tts", "{}")
        );
    }

    static Stream<Arguments> loginOnlyEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/llm-provider/list", null),
                Arguments.of(HttpMethod.GET, "/api/llm-provider/p1", null),
                Arguments.of(HttpMethod.GET, "/api/llm-provider/default-provider", null),
                Arguments.of(HttpMethod.GET, "/api/llm-provider/gateway/status", null),
                Arguments.of(HttpMethod.GET, "/api/llm-provider/voice/asr", null)
        );
    }

    private MockHttpServletRequestBuilder call(HttpMethod method, String url, String body, Role role) {
        MockHttpServletRequestBuilder req = request(method, url);
        if (role != null) {
            req.header("Authorization", "Bearer " + jwtService.generateToken("u-" + role, 1L, role));
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return req;
    }

    @ParameterizedTest(name = "USER {0} {1} → 403")
    @MethodSource("adminOnlyEndpoints")
    void normalUserGetsForbiddenOnWriteEndpoints(HttpMethod method, String url, String body) throws Exception {
        mvc.perform(call(method, url, body, Role.USER)).andExpect(status().isForbidden());
        // 被拒绝的请求绝不能触达业务层
        verifyNoInteractions(configService);
    }

    @ParameterizedTest(name = "旧 token(无 role claim) {0} {1} → 403")
    @MethodSource("adminOnlyEndpoints")
    void legacyTokenWithoutRoleIsTreatedAsUser(HttpMethod method, String url, String body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, url)
                .header("Authorization", "Bearer " + jwtService.generateToken("legacy", 1L));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(req).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "ADMIN {0} {1} → 200")
    @MethodSource("adminOnlyEndpoints")
    void adminCanCallWriteEndpoints(HttpMethod method, String url, String body) throws Exception {
        mvc.perform(call(method, url, body, Role.ADMIN)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "匿名 {0} {1} → 401")
    @MethodSource("adminOnlyEndpoints")
    void anonymousGetsUnauthorized(HttpMethod method, String url, String body) throws Exception {
        mvc.perform(call(method, url, body, null)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "USER {0} {1} → 200")
    @MethodSource("loginOnlyEndpoints")
    void normalUserCanQuery(HttpMethod method, String url, String body) throws Exception {
        mvc.perform(call(method, url, body, Role.USER)).andExpect(status().isOk());
    }
}
