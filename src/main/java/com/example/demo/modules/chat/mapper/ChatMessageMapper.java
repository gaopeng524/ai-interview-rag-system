package com.example.demo.modules.chat.mapper;

import com.example.demo.modules.chat.model.entity.ChatMessage;
import com.example.demo.modules.chat.model.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    int insert(ChatMessage chatMessage);
    List<ChatMessage> findBySessionId(Long sessionId);
    int deleteBySessionId(Long sessionId);
    
    /**
     * 获取会话中最近 N 条消息（按时间倒序）
     * 用于加载历史上下文
     */
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") Long sessionId, @Param("limit") int limit);
}