package com.example.demo.modules.resume.controller;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.common.result.Result;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.resume.model.dto.ResumeDTO.*;
import com.example.demo.modules.resume.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 简历管理与智能审计控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    /**
     * 上传简历并执行 AI 深度分析打分
     * POST /api/resumes/upload
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60, message = "上传简历过于频繁，请稍候重试")
    public Result<UploadResponse> uploadAndAnalyze(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        UploadResponse resp = resumeService.uploadAndAnalyze(file, userId);
        return Result.success(resp.message(), resp);
    }

    /**
     * 获取用户所有简历列表
     * GET /api/resumes
     */
    @GetMapping
    public Result<List<ResumeListItem>> getAllResumes(@AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        return Result.success(resumeService.getAllResumes(userId));
    }

    /**
     * 获取简历详情与诊断报告复盘
     * GET /api/resumes/{id}/detail
     */
    @GetMapping("/{id}/detail")
    public Result<ResumeDetailDTO> getResumeDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        return Result.success(resumeService.getResumeDetail(id, userId));
    }

    /**
     * 重新分析简历
     * POST /api/resumes/{id}/reanalyze
     */
    @PostMapping("/{id}/reanalyze")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 60, message = "操作过于频繁，请稍后重试")
    public Result<ResumeAnalysisResponse> reanalyze(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        return Result.success(resumeService.reanalyze(id, userId));
    }

    /**
     * 删除简历及分析记录
     * DELETE /api/resumes/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteResume(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        resumeService.deleteResume(id, userId);
        return Result.success(null);
    }

    /**
     * 导出简历评估报告为 PDF 文件
     * GET /api/resumes/{id}/export
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportAnalysis(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long userId = principal != null ? principal.id() : 1L;
        byte[] pdfBytes = resumeService.exportAnalysisPdf(id, userId);
        String filename = URLEncoder.encode("resume-analysis-report-" + id + ".pdf", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
