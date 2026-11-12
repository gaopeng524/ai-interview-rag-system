package com.example.demo.modules.user.service;

import com.example.demo.modules.user.model.dto.UserDTO;
import com.example.demo.modules.user.model.entity.User;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.user.mapper.UserMapper;
import com.example.demo.common.security.JwtService;
import com.example.demo.common.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** 用户列表：只返回脱敏 DTO，绝不含密码字段。 */
    public List<UserDTO.UserResponse> findAll() {
        return userMapper.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public UserDTO.UserResponse findByIdResponse(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            return null;
        }
        return toResponse(user);
    }

    public UserDTO.UserResponse createUser(User user) {
        // 1. 密码加密
        String encodedPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(encodedPassword);
        // 注册接口直接绑定 User 实体，必须强制覆盖 role，防止请求体里塞 "role":"ADMIN" 自助提权。
        // 管理员只能由 DBA 通过 SQL 授予（见 db/migration/V2__add_user_role.sql 注释）。
        user.setRole(Role.USER.name());

        // 2. 保存数据库
        userMapper.insert(user);

        // 3. Entity -> DTO
        return toResponse(user);
    }

    public String login(UserDTO.LoginRequest request) {
        User user = userMapper.findByUsername(request.username());

        // 用户名不存在和密码错误用同一条提示：避免泄露"这个用户名有没有注册过"
        boolean matches = user != null
                && passwordEncoder.matches(request.password(), user.getPassword());

        if (!matches) {
            // 业务异常自带 401，全局异常处理器会按 401 返回，而不是 500。
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        return jwtService.generateToken(user.getUsername(), user.getId(), Role.fromNullable(user.getRole()));
    }

    /** User 实体 → 脱敏 DTO：只暴露 id 和 username，永远不带 password。 */
    private UserDTO.UserResponse toResponse(User user) {
        return new UserDTO.UserResponse(user.getId(), user.getUsername());
    }
}
