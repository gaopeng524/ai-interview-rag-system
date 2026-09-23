package com.example.demo.modules.chat.mapper;

import com.example.demo.modules.chat.model.entity.ChatSession;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    int insert(ChatSession chatSession);

    ChatSession findByIdAndUserId(
            @Param("id") Long id,
            @Param("userId") Long userId
    );

    List<ChatSession> findByUserId(@Param("userId") Long userId);

    int deleteById(@Param("id") Long id);

    int updateTitle(@Param("id") Long id, @Param("title") String title);

    int togglePin(@Param("id") Long id);

    int updateUpdatedAt(@Param("id") Long id);

    int insertSessionKnowledgeBases(@Param("sessionId") Long sessionId, @Param("kbIds") List<Long> kbIds);

    int deleteSessionKnowledgeBases(@Param("sessionId") Long sessionId);

    List<Long> findKnowledgeBaseIdsBySessionId(@Param("sessionId") Long sessionId);

    List<KnowledgeBase> findKnowledgeBasesBySessionId(@Param("sessionId") Long sessionId);

    int countMessagesBySessionId(@Param("sessionId") Long sessionId);
}