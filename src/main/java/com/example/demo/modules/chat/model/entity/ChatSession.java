package com.example.demo.modules.chat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatSession {

    private Long id;

    private Long userId;

    private String title;

    private Boolean isPinned = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}