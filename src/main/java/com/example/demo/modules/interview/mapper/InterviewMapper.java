package com.example.demo.modules.interview.mapper;

import com.example.demo.modules.interview.model.entity.InterviewQuestionEntity;
import com.example.demo.modules.interview.model.entity.InterviewSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模拟面试持久化 Mapper
 */
@Mapper
public interface InterviewMapper {

    // ========== 会话操作 ==========

    int insertSession(InterviewSessionEntity session);

    InterviewSessionEntity findSessionBySessionId(@Param("sessionId") String sessionId);

    InterviewSessionEntity findUnfinishedSession(@Param("userId") Long userId, @Param("resumeId") Long resumeId);

    List<InterviewSessionEntity> findSessionsByUserId(@Param("userId") Long userId);

    List<InterviewSessionEntity> findSessionsByResumeId(@Param("resumeId") Long resumeId);

    int updateSessionStatus(@Param("sessionId") String sessionId,
                            @Param("status") String status,
                            @Param("currentQuestionIndex") Integer currentQuestionIndex);

    int updateSessionEvaluation(InterviewSessionEntity session);

    int deleteSessionBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    // ========== 题目与作答操作 ==========

    int batchInsertQuestions(@Param("questions") List<InterviewQuestionEntity> questions);

    List<InterviewQuestionEntity> findQuestionsBySessionId(@Param("sessionId") String sessionId);

    InterviewQuestionEntity findQuestionByIndex(@Param("sessionId") String sessionId, @Param("questionIndex") int questionIndex);

    int updateUserAnswer(@Param("sessionId") String sessionId,
                         @Param("questionIndex") int questionIndex,
                         @Param("userAnswer") String userAnswer);

    int updateQuestionEvaluation(InterviewQuestionEntity question);

    int deleteQuestionsBySessionId(@Param("sessionId") String sessionId);
}
