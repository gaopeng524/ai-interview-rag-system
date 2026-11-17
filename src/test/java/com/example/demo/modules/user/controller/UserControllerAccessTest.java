package com.example.demo.modules.user.controller;

import com.example.demo.common.config.SecurityConfig;
import com.example.demo.common.exception.GlobalExceptionHandler;
import com.example.demo.common.filter.JwtAuthenticationFilter;
import com.example.demo.common.security.JwtService;
import com.example.demo.common.security.Role;
import com.example.demo.modules.user.model.dto.UserDTO;
import com.example.demo.modules.user.service.UserService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(UserControllerAccessTest.TestConfig.class)
@DisplayName("用户接口：列表仅管理员，详情仅本人或管理员")
class UserControllerAccessTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, UserController.class})
    static class TestConfig {
        @Bean JwtService jwtService() {
            return new JwtService("unit-test-secret-key-unit-test-secret-key-0123456789", 3_600_000L);
        }
        @Bean UserService userService() { return mock(UserService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtService jwtService;
    @Autowired UserService userService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(userService);
        when(userService.findAll()).thenReturn(List.of());
        when(userService.findByIdResponse(anyLong())).thenReturn(new UserDTO.UserResponse(1L, "u"));
    }

    private String bearer(long id, Role role) {
        return "Bearer " + jwtService.generateToken("u" + id, id, role);
    }

    @Test
    @DisplayName("未登录 → 401")
    void anonymous() throws Exception {
        mvc.perform(get("/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("普通用户：不能列出全部用户，不能查看他人，可以查看自己")
    void normalUser() throws Exception {
        String auth = bearer(1L, Role.USER);
        mvc.perform(get("/users").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/users/2").header("Authorization", auth)).andExpect(status().isForbidden());
        verify(userService, never()).findAll();
        verify(userService, never()).findByIdResponse(2L);
        mvc.perform(get("/users/1").header("Authorization", auth)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("管理员：可以列出全部用户、查看任意用户")
    void admin() throws Exception {
        String auth = bearer(9L, Role.ADMIN);
        mvc.perform(get("/users").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/users/2").header("Authorization", auth)).andExpect(status().isOk());
    }
}
