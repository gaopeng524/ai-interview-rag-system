package com.example.demo.modules.user.controller;

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

    @GetMapping
    public Result<List<UserDTO.UserResponse>> findAll() {
        return Result.success(userService.findAll());
    }

    @GetMapping("/{id}")
    public Result<UserDTO.UserResponse> findById(@PathVariable Long id) {
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
