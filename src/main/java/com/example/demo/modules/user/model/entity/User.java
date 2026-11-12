package com.example.demo.modules.user.model.entity;

import lombok.Data;

@Data
public class User {

    private Long id;

    private String username;

    private String password;

    /** 角色：USER / ADMIN，对应 users.role，数据库默认 USER */
    private String role;


}