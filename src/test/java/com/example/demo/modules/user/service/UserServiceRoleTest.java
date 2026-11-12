package com.example.demo.modules.user.service;

import com.example.demo.common.security.JwtService;
import com.example.demo.common.security.Role;
import com.example.demo.modules.user.mapper.UserMapper;
import com.example.demo.modules.user.model.dto.UserDTO;
import com.example.demo.modules.user.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@DisplayName("UserService 角色")
class UserServiceRoleTest {

    private UserMapper userMapper;
    private JwtService jwtService;
    private PasswordEncoder encoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        encoder = new BCryptPasswordEncoder(4);
        jwtService = new JwtService("unit-test-secret-key-unit-test-secret-key-0123456789", 3_600_000L);
        userService = new UserService(userMapper, encoder, jwtService);
    }

    @Test
    @DisplayName("注册时请求体里带 role=ADMIN 也只会创建 USER")
    void registrationCannotSelfGrantAdmin() {
        User req = new User();
        req.setUsername("mallory");
        req.setPassword("pwd");
        req.setRole("ADMIN");

        userService.createUser(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(saved.capture());
        assertEquals("USER", saved.getValue().getRole());
    }

    @Test
    @DisplayName("登录签发的 JWT 携带数据库中的角色")
    void loginTokenCarriesRole() {
        User admin = new User();
        admin.setId(7L);
        admin.setUsername("admin");
        admin.setPassword(encoder.encode("pwd"));
        admin.setRole("ADMIN");
        when(userMapper.findByUsername("admin")).thenReturn(admin);

        String token = userService.login(new UserDTO.LoginRequest("admin", "pwd"));

        assertEquals("ADMIN", jwtService.extractClaims(token).get(JwtService.CLAIM_ROLE, String.class));
    }

    @Test
    @DisplayName("数据库角色为空或非法时按 USER 签发")
    void unknownRoleFallsBackToUser() {
        assertEquals(Role.USER, Role.fromNullable(null));
        assertEquals(Role.USER, Role.fromNullable("superuser"));
        assertEquals(Role.ADMIN, Role.fromNullable("admin"));
    }
}
