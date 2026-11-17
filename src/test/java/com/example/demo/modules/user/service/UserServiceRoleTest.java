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
        req.setPassword("pwd-123456");
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

    private static User user(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(password);
        return u;
    }

    private void assertRejected(User u) {
        com.example.demo.common.exception.BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.example.demo.common.exception.BusinessException.class, () -> userService.createUser(u));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("注册校验：空用户名 / 空密码 / 过短 / 过长都被拒绝，不写库")
    void registrationValidation() {
        assertRejected(user(null, "pwd-123456"));
        assertRejected(user("alice", null));
        assertRejected(user("  ", "pwd-123456"));
        assertRejected(user("ab", "pwd-123456"));
        assertRejected(user("a".repeat(33), "pwd-123456"));
        assertRejected(user("alice", "12345"));
        assertRejected(user("alice", "x".repeat(73)));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("用户名已存在：返回 400 而不是 500；并发注册由唯一约束兜底")
    void duplicateUsername() {
        when(userMapper.findByUsername("alice")).thenReturn(new User());
        assertRejected(user("alice", "pwd-123456"));

        when(userMapper.findByUsername("bob")).thenReturn(null);
        doThrow(new org.springframework.dao.DuplicateKeyException("uk")).when(userMapper).insert(any());
        assertRejected(user("bob", "pwd-123456"));
    }

    @Test
    @DisplayName("注册时忽略请求体里的 id，用户名去掉首尾空格")
    void registrationNormalizesInput() {
        User u = user("  carol ", "pwd-123456");
        u.setId(999L);
        userService.createUser(u);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(saved.capture());
        assertEquals("carol", saved.getValue().getUsername());
        org.junit.jupiter.api.Assertions.assertNull(saved.getValue().getId());
    }
}
