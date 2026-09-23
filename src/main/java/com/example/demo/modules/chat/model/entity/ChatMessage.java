package com.example.demo.modules.chat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessage {

    private Long id;

    private Long sessionId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}