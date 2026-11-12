package com.example.demo.modules.resume.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.util.PdfExportService;
import com.example.demo.modules.interview.mapper.InterviewMapper;
import com.example.demo.modules.resume.mapper.ResumeMapper;
import com.example.demo.modules.resume.model.entity.ResumeAnalysisEntity;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 简历按 id 访问的归属校验：未登录（userId=null）/ 访问他人简历 / 访问自己简历。
 */
@DisplayName("ResumeService 归属校验")
class ResumeServiceOwnershipTest {

    private static final long OWNER_ID = 100L;
    private static final long OTHER_ID = 200L;
    private static final long RESUME_ID = 1L;

    private ResumeMapper resumeMapper;
    private InterviewMapper interviewMapper;
    private PdfExportService pdfExportService;
    private ResumeService service;

    @BeforeEach
    void setUp() {
        resumeMapper = mock(ResumeMapper.class);
        interviewMapper = mock(InterviewMapper.class);
        pdfExportService = mock(PdfExportService.class);
        service = new ResumeService(resumeMapper, mock(DocumentParseService.class), null,
                new ObjectMapper(), pdfExportService, interviewMapper, null, null);

        ResumeEntity resume = new ResumeEntity();
        resume.setId(RESUME_ID);
        resume.setUserId(OWNER_ID);
        resume.setResumeText("张三的简历");
        when(resumeMapper.findResumeById(RESUME_ID)).thenReturn(resume);
        when(resumeMapper.findAnalysesByResumeId(RESUME_ID)).thenReturn(List.of());
        when(interviewMapper.findSessionsByResumeId(RESUME_ID)).thenReturn(List.of());
    }

    private static void assertForbidden(Executable call) {
        BusinessException ex = assertThrows(BusinessException.class, call);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Nested
    @DisplayName("未登录（userId 为空）")
    class Anonymous {
        @Test
        void allIdBasedOperationsAreForbidden() {
            assertForbidden(() -> service.getResumeDetail(RESUME_ID, null));
            assertForbidden(() -> service.deleteResume(RESUME_ID, null));
            assertForbidden(() -> service.reanalyze(RESUME_ID, null));
            assertForbidden(() -> service.exportAnalysisPdf(RESUME_ID, null));
            assertForbidden(() -> service.getResumeTextAndIncrementInterviewCount(RESUME_ID, null));
            // userId 为空时连库都不查，避免通过 404/403 差异探测 id 是否存在
            verify(resumeMapper, never()).findResumeById(anyLong());
            verifyNoWrites();
        }
    }

    @Nested
    @DisplayName("访问他人简历")
    class OtherUser {
        @Test
        void allIdBasedOperationsAreForbidden() {
            assertForbidden(() -> service.getResumeDetail(RESUME_ID, OTHER_ID));
            assertForbidden(() -> service.deleteResume(RESUME_ID, OTHER_ID));
            assertForbidden(() -> service.reanalyze(RESUME_ID, OTHER_ID));
            assertForbidden(() -> service.exportAnalysisPdf(RESUME_ID, OTHER_ID));
            assertForbidden(() -> service.getResumeTextAndIncrementInterviewCount(RESUME_ID, OTHER_ID));
            verifyNoWrites();
        }

        @Test
        void resumeWithoutOwnerIsForbidden() {
            ResumeEntity orphan = new ResumeEntity();
            orphan.setId(2L);
            orphan.setUserId(null);
            when(resumeMapper.findResumeById(2L)).thenReturn(orphan);
            assertForbidden(() -> service.getResumeDetail(2L, OWNER_ID));
        }
    }

    @Nested
    @DisplayName("访问自己的简历")
    class Owner {
        @Test
        void detailSucceeds() {
            assertNotNull(service.getResumeDetail(RESUME_ID, OWNER_ID));
            verify(resumeMapper).incrementAccessCount(RESUME_ID);
        }

        @Test
        void deleteSucceeds() {
            service.deleteResume(RESUME_ID, OWNER_ID);
            verify(resumeMapper).deleteAnalysesByResumeId(RESUME_ID);
            verify(resumeMapper).deleteResumeById(RESUME_ID, OWNER_ID);
        }

        @Test
        void exportSucceeds() {
            ResumeAnalysisEntity analysis = new ResumeAnalysisEntity();
            when(resumeMapper.findLatestAnalysisByResumeId(RESUME_ID)).thenReturn(analysis);
            when(pdfExportService.exportResumeAnalysisPdf(any(), any())).thenReturn(new byte[]{1});
            assertArrayEquals(new byte[]{1}, service.exportAnalysisPdf(RESUME_ID, OWNER_ID));
        }

        @Test
        void interviewLinkageReturnsText() {
            assertEquals("张三的简历", service.getResumeTextAndIncrementInterviewCount(RESUME_ID, OWNER_ID));
            verify(resumeMapper).incrementInterviewCount(RESUME_ID);
        }
    }

    @Test
    @DisplayName("不存在的简历返回 404")
    void notFound() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getResumeDetail(999L, OWNER_ID));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    private void verifyNoWrites() {
        verify(resumeMapper, never()).deleteResumeById(anyLong(), any());
        verify(resumeMapper, never()).deleteAnalysesByResumeId(anyLong());
        verify(resumeMapper, never()).incrementAccessCount(anyLong());
        verify(resumeMapper, never()).incrementInterviewCount(anyLong());
        verify(resumeMapper, never()).updateResumeAnalysisResult(anyLong(), any(), any(), any());
    }
}
