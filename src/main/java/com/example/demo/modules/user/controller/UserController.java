package com.example.demo.modules.user.controller;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.security.CurrentUser;
import com.example.demo.common.security.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.example.demo.modules.user.model.dto.UserDTO;
import com.example.demo.modules.user.model.entity.User;
import com.example.demo.common.result.Result;
import com.example.demo.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理控制器（薄 Controller）
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 用户列表：仅管理员可见（原来任何登录用户都能列出全部账号） */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<UserDTO.UserResponse>> findAll() {
        return Result.success(userService.findAll());
    }

    /** 用户信息：只能查自己，管理员可查任意用户 */
    @GetMapping("/{id}")
    public Result<UserDTO.UserResponse> findById(@PathVariable Long id,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = CurrentUser.requireId(principal);
        if (!currentUserId.equals(id) && !principal.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看其他用户的信息");
        }
        return Result.success(userService.findByIdResponse(id));
    }

    @PostMapping
    public Result<UserDTO.UserResponse> createUser(@RequestBody User user) {
        return Result.success(userService.createUser(user));
    }

    @PostMapping("/login")
    public String login(@RequestBody UserDTO.LoginRequest request) {
        return userService.login(request);
    }
}
