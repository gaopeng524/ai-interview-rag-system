package com.example.demo.modules.interview.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.interview.async.InterviewEvaluateStreamProducer;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import com.example.demo.modules.interview.model.dto.InterviewDTO.CreateInterviewRequest;
import com.example.demo.modules.interview.model.dto.InterviewDTO.SubmitAnswerRequest;
import com.example.demo.modules.interview.model.entity.InterviewSessionEntity;
import com.example.demo.modules.resume.mapper.ResumeMapper;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import com.example.demo.modules.resume.service.DocumentParseService;
import com.example.demo.modules.resume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 面试会话按 sessionId 访问的归属校验：未登录（userId=null）/ 访问他人会话 / 访问自己会话。
 */
@DisplayName("InterviewService 归属校验")
class InterviewServiceOwnershipTest {

    private static final long OWNER_ID = 100L;
    private static final long OTHER_ID = 200L;
    private static final String SESSION_ID = "session-owner";

    private InterviewMapper interviewMapper;
    private ResumeMapper resumeMapper;
    private InterviewEvaluateStreamProducer producer;
    private InterviewService service;

    @BeforeEach
    void setUp() {
        interviewMapper = mock(InterviewMapper.class);
        resumeMapper = mock(ResumeMapper.class);
        producer = mock(InterviewEvaluateStreamProducer.class);
        ResumeService resumeService = new ResumeService(resumeMapper, mock(DocumentParseService.class), null,
                new ObjectMapper(), null, interviewMapper, null, null);
        service = new InterviewService(interviewMapper, null, null, null, resumeService, producer, null, new ObjectMapper());

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(SESSION_ID);
        session.setUserId(OWNER_ID);
        session.setTotalQuestions(3);
        session.setCurrentQuestionIndex(0);
        session.setStatus("IN_PROGRESS");
        session.setEvaluateStatus("PENDING");
        when(interviewMapper.findSessionBySessionId(SESSION_ID)).thenReturn(session);
        when(interviewMapper.findQuestionsBySessionId(SESSION_ID)).thenReturn(List.of());
    }

    private static void assertForbidden(Executable call) {
        BusinessException ex = assertThrows(BusinessException.class, call);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    /** 所有按 sessionId 访问的公开方法，统一用同一个 userId 调一遍 */
    private void assertAllSessionOperationsForbidden(Long userId) {
        assertForbidden(() -> service.getSession(SESSION_ID, userId));
        assertForbidden(() -> service.getCurrentQuestion(SESSION_ID, userId));
        assertForbidden(() -> service.submitAnswer(userId, new SubmitAnswerRequest(SESSION_ID, 0, "答案")));
        assertForbidden(() -> service.saveAnswer(userId, new SubmitAnswerRequest(SESSION_ID, 0, "答案")));
        assertForbidden(() -> service.completeInterview(SESSION_ID, userId));
        assertForbidden(() -> service.generateReport(SESSION_ID, userId));
        assertForbidden(() -> service.getInterviewDetails(SESSION_ID, userId));
        assertForbidden(() -> service.exportInterviewPdf(SESSION_ID, userId));
        assertForbidden(() -> service.deleteSession(SESSION_ID, userId));

        verify(interviewMapper, never()).updateUserAnswer(anyString(), anyInt(), anyString());
        verify(interviewMapper, never()).updateSessionStatus(anyString(), anyString(), anyInt());
        verify(interviewMapper, never()).updateSessionEvaluation(any());
        verify(interviewMapper, never()).deleteQuestionsBySessionId(anyString());
        verify(interviewMapper, never()).deleteSessionBySessionId(anyString(), any());
        verifyNoInteractions(producer);
    }

    @Nested
    @DisplayName("未登录（userId 为空）")
    class Anonymous {
        @Test
        void allSessionOperationsAreForbidden() {
            assertAllSessionOperationsForbidden(null);
            verify(interviewMapper, never()).findSessionBySessionId(anyString());
        }
    }

    @Nested
    @DisplayName("访问他人会话")
    class OtherUser {
        @Test
        void allSessionOperationsAreForbidden() {
            assertAllSessionOperationsForbidden(OTHER_ID);
        }

        @Test
        void sessionWithoutOwnerIsForbidden() {
            InterviewSessionEntity orphan = new InterviewSessionEntity();
            orphan.setSessionId("orphan");
            when(interviewMapper.findSessionBySessionId("orphan")).thenReturn(orphan);
            assertForbidden(() -> service.getSession("orphan", OWNER_ID));
        }

        @Test
        void cannotCreateInterviewFromSomeoneElsesResume() {
            ResumeEntity resume = new ResumeEntity();
            resume.setId(9L);
            resume.setUserId(OWNER_ID);
            resume.setResumeText("机密简历");
            when(resumeMapper.findResumeById(9L)).thenReturn(resume);

            CreateInterviewRequest req = new CreateInterviewRequest(null, 3, 9L, false, null,
                    "java-backend", "mid", null, null, null, null);
            assertForbidden(() -> service.createSession(OTHER_ID, req));
            verify(interviewMapper, never()).insertSession(any());
            verify(resumeMapper, never()).incrementInterviewCount(anyLong());
        }
    }

    @Nested
    @DisplayName("访问自己的会话")
    class Owner {
        @Test
        void readOperationsSucceed() {
            assertEquals(SESSION_ID, service.getSession(SESSION_ID, OWNER_ID).sessionId());
            assertNotNull(service.getCurrentQuestion(SESSION_ID, OWNER_ID));
            assertNotNull(service.getInterviewDetails(SESSION_ID, OWNER_ID));
        }

        @Test
        void saveAnswerWrites() {
            service.saveAnswer(OWNER_ID, new SubmitAnswerRequest(SESSION_ID, 0, "答案"));
            verify(interviewMapper).updateUserAnswer(SESSION_ID, 0, "答案");
        }

        @Test
        void completeTriggersEvaluation() {
            service.completeInterview(SESSION_ID, OWNER_ID);
            verify(interviewMapper).updateSessionStatus(SESSION_ID, "COMPLETED", 0);
            verify(producer).sendEvaluateTask(SESSION_ID, OWNER_ID);
        }

        @Test
        void deleteSucceeds() {
            service.deleteSession(SESSION_ID, OWNER_ID);
            verify(interviewMapper).deleteQuestionsBySessionId(SESSION_ID);
            verify(interviewMapper).deleteSessionBySessionId(SESSION_ID, OWNER_ID);
        }
    }

    @Test
    @DisplayName("不存在的会话返回 404")
    void notFound() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getSession("nope", OWNER_ID));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }
}
