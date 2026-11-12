package com.example.demo.modules.resume.service;

import com.example.demo.common.ai.AiStructuredClient;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.util.PdfExportService;
import com.example.demo.modules.resume.mapper.ResumeMapper;
import com.example.demo.modules.resume.model.dto.ResumeDTO.*;
import com.example.demo.modules.resume.model.entity.ResumeAnalysisEntity;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import com.example.demo.modules.resume.prompt.ResumePrompts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 简历资产管理与智能分析业务服务（内置 Token 消耗、缓存命中与深度审计全链路监控）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final DocumentParseService documentParseService;
    private final AiStructuredClient aiStructuredClient;
    private final ObjectMapper objectMapper;
    private final PdfExportService pdfExportService;
    private final com.example.demo.modules.interview.mapper.InterviewMapper interviewMapper;
    private final com.example.demo.common.ai.Mem0Client mem0Client;
    private final com.example.demo.common.ai.PromptSanitizer promptSanitizer;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 上传简历并由 AI 执行深度结构化分析（支持秒传与历史复用）
     */
    @Transactional
    public UploadResponse uploadAndAnalyze(MultipartFile file, Long userId) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "未命名简历";
        long fileSize = file.getSize();
        String fileHash = documentParseService.calculateHash(file);
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        // 1. 基于 SHA-256 哈希查重：若用户已上传过完全相同的文件，直接复用历史分析报告
        ResumeEntity existing = resumeMapper.findResumeByHash(userId, fileHash);
        if (existing != null) {
            resumeMapper.incrementAccessCount(existing.getId());
            ResumeAnalysisEntity latestAnalysis = resumeMapper.findLatestAnalysisByResumeId(existing.getId());
            if (latestAnalysis != null) {
                log.info("""
                        
                        ==================== ⚡ [简历秒传与缓存命中] ====================
                        📁 文件: {} (大小: {} bytes)
                        🔑 SHA-256 哈希: {}
                        🎯 状态: 命中历史分析报告 (resumeId={})，直接复用，免除大模型调用
                        📊 历史得分: {} 分
                        ===============================================================""",
                        filename, fileSize, fileHash, existing.getId(), latestAnalysis.getOverallScore()
                );
                ResumeAnalysisResponse resp = mapToAnalysisResponse(latestAnalysis, existing.getResumeText());
                StorageInfo storage = new StorageInfo("resumes/" + existing.getId(), "/api/resumes/" + existing.getId(), existing.getId());
                return new UploadResponse(resp, storage, true, "检测到相同简历，已返回历史分析结果");
            }
        }

        // 2. 解析文档纯文本
        String resumeText = documentParseService.parseContent(file);
        log.info("""
                
                ==================== 📄 [新简历上传与解析] ====================
                📁 文件: {} (大小: {} bytes, 提取字符数: {})
                🔑 SHA-256 哈希: {}
                🚀 状态: 未命中历史记录，正在调用大模型执行五维深度审计...
                ===============================================================""",
                filename, fileSize, resumeText.length(), fileHash
        );

        // 3. 初始持久化简历记录
        ResumeEntity resume = ResumeEntity.create(userId, filename, fileSize, fileHash, contentType, resumeText);
        resumeMapper.insertResume(resume);

        // 4. 调用 AI 执行深度多维技术审计与评分并持久化
        ResumeAnalysisResponse analysisResponse = analyzeResumeInternal(resumeText);
        saveAnalysisResult(resume.getId(), analysisResponse);

        log.info("""
                
                ==================== 🏆 [简历深度审计完成] ====================
                🆔 resumeId: {} | 综合得分: {} 分
                📊 五维得分: 内容={}分, 结构={}分, 技能={}分, 表达={}分, 项目={}分
                💡 优势亮点数: {} 条 | STAR 优化建议数: {} 条
                ===============================================================""",
                resume.getId(), analysisResponse.overallScore(),
                analysisResponse.scoreDetail().contentScore(),
                analysisResponse.scoreDetail().structureScore(),
                analysisResponse.scoreDetail().skillMatchScore(),
                analysisResponse.scoreDetail().expressionScore(),
                analysisResponse.scoreDetail().projectScore(),
                analysisResponse.strengths().size(),
                analysisResponse.suggestions().size()
        );

        StorageInfo storage = new StorageInfo("resumes/" + resume.getId(), "/api/resumes/" + resume.getId(), resume.getId());

        // 异步向 Mem0 沉淀该用户的初始技术画像种子
        if (mem0Client != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String resumeSummary = String.format("考生简历核心特征: %s, 综合得分: %.1f, 技术优势: %s",
                            analysisResponse.summary(),
                            analysisResponse.overallScore(),
                            analysisResponse.strengths() != null ? String.join(", ", analysisResponse.strengths()) : "无");
                    mem0Client.addMemory(resumeSummary, userId);
                } catch (Exception e) {
                    log.debug("Mem0 异步记录简历记忆失败: {}", e.getMessage());
                }
            });
        }

        return new UploadResponse(analysisResponse, storage, false, "简历上传并分析完成");
    }

    /**
     * 获取用户所有简历列表
     */
    public List<ResumeListItem> getAllResumes(Long userId) {
        return resumeMapper.findResumesByUserId(userId).stream()
                .map(r -> ResumeListItem.from(r, DATE_TIME_FORMATTER))
                .toList();
    }

    /**
     * 获取简历全量详情（包含历史分析记录与面试历史）
     */
    public ResumeDetailDTO getResumeDetail(Long id, Long userId) {
        ResumeEntity resume = validateResumeOwner(id, userId);
        resumeMapper.incrementAccessCount(id);

        List<AnalysisItem> history = resumeMapper.findAnalysesByResumeId(id).stream()
                .map(this::toAnalysisItem)
                .toList();

        List<InterviewItemDTO> interviewItems = interviewMapper != null
                ? interviewMapper.findSessionsByResumeId(id).stream()
                        .map(s -> InterviewItemDTO.from(s, DATE_TIME_FORMATTER))
                        .toList()
                : List.of();

        return ResumeDetailDTO.of(resume, history, interviewItems, DATE_TIME_FORMATTER);
    }

    /**
     * 删除简历及分析报告
     */
    @Transactional
    public void deleteResume(Long id, Long userId) {
        validateResumeOwner(id, userId);
        resumeMapper.deleteAnalysesByResumeId(id);
        resumeMapper.deleteResumeById(id, userId);
    }

    /**
     * 手动触发重新分析
     */
    @Transactional
    public ResumeAnalysisResponse reanalyze(Long id, Long userId) {
        ResumeEntity resume = validateResumeOwner(id, userId);
        resumeMapper.updateResumeAnalysisResult(id, "PROCESSING", null, null);

        log.info("🔄 [手动重新审计] 正在为简历 resumeId={} 重新生成五维评估报告...", id);
        ResumeAnalysisResponse analysisResponse = analyzeResumeInternal(resume.getResumeText());
        saveAnalysisResult(resume.getId(), analysisResponse);

        return analysisResponse;
    }

    /**
     * 导出简历评估报告为标准 PDF 字节流
     */
    public byte[] exportAnalysisPdf(Long id, Long userId) {
        ResumeEntity resume = validateResumeOwner(id, userId);
        ResumeAnalysisEntity latestAnalysis = resumeMapper.findLatestAnalysisByResumeId(id);
        if (latestAnalysis == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该简历尚未完成分析评估，无法导出报告");
        }

        ResumeAnalysisResponse resp = mapToAnalysisResponse(latestAnalysis, resume.getResumeText());
        return pdfExportService.exportResumeAnalysisPdf(resume, resp);
    }

    /**
     * 面试模块联动调用：获取简历文本并递增面试计数
     */
    public String getResumeTextAndIncrementInterviewCount(Long resumeId, Long userId) {
        if (resumeId == null) return "";
        // 必须做归属校验：否则用别人的 resumeId 建面试，就能把别人的简历全文读进自己的会话
        ResumeEntity resume = validateResumeOwner(resumeId, userId);
        resumeMapper.incrementInterviewCount(resumeId);
        return resume.getResumeText() != null ? resume.getResumeText() : "";
    }

    // ==================== 私有辅助 ====================

    /**
     * 简历归属校验（按 id 访问简历的所有入口都必须经过这里）。
     * <ul>
     *   <li>userId 为空：调用方没有登录身份，直接 403，不再"跳过校验"；</li>
     *   <li>简历不存在：404；</li>
     *   <li>简历没有归属人（历史脏数据）或归属人不是当前用户：403。</li>
     * </ul>
     * 先判 userId 再查库，避免匿名请求通过 404/403 的差异探测 id 是否存在。
     */
    ResumeEntity validateResumeOwner(Long id, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该简历");
        }
        ResumeEntity resume = resumeMapper.findResumeById(id);
        if (resume == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历不存在");
        }
        if (!userId.equals(resume.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该简历");
        }
        return resume;
    }

    private ResumeAnalysisResponse analyzeResumeInternal(String resumeText) {
        String safeResume = promptSanitizer != null
                ? promptSanitizer.wrapWithBoundary("resume-text", resumeText)
                : resumeText;
        String prompt = ResumePrompts.buildAnalyzeResumePrompt(safeResume);
        return aiStructuredClient.callForStructured(
                prompt,
                new TypeReference<ResumeAnalysisResponse>() {},
                () -> generateFallbackAnalysis(resumeText)
        );
    }

    private ResumeAnalysisResponse generateFallbackAnalysis(String resumeText) {
        ScoreDetail scoreDetail = new ScoreDetail(20.0, 16.0, 20.0, 12.0, 12.0);
        return new ResumeAnalysisResponse(
                80.0,
                scoreDetail,
                "候选人技术体系基本完整，具备实际项目落地经验，建议补充技术细节与量化指标。",
                List.of("技术栈覆盖主流后端开发组件", "项目经历具备一定的业务体量", "排版结构较规范"),
                List.of(
                        new Suggestion("项目经验", "高", "项目描述偏职责说明，缺少核心技术难点与量化成果",
                                "建议采用 STAR 法则：阐述遇到了什么高并发/性能瓶颈，采取了什么架构优化，取得了什么 QPS/响应时间提升。"),
                        new Suggestion("专业技能", "中", "技能描述多使用‘精通/熟练’等定性词汇",
                                "建议结合具体原理机制展开，如‘深入理解 JVM 内存模型、GC 回收器及线上故障排查’。")
                ),
                resumeText
        );
    }

    private ResumeAnalysisEntity saveAnalysisResult(Long resumeId, ResumeAnalysisResponse analysisResponse) {
        ResumeAnalysisEntity analysisEntity = ResumeAnalysisEntity.create(
                resumeId,
                analysisResponse,
                toJson(analysisResponse.strengths(), "[]"),
                toJson(analysisResponse.suggestions(), "[]")
        );
        resumeMapper.insertAnalysis(analysisEntity);
        resumeMapper.updateResumeAnalysisResult(
                resumeId, "COMPLETED", null, analysisEntity.getOverallScore());
        return analysisEntity;
    }

    private ResumeAnalysisResponse mapToAnalysisResponse(ResumeAnalysisEntity a, String originalText) {
        ScoreDetail detail = new ScoreDetail(
                a.getContentScore() != null ? a.getContentScore().doubleValue() : 0.0,
                a.getStructureScore() != null ? a.getStructureScore().doubleValue() : 0.0,
                a.getSkillMatchScore() != null ? a.getSkillMatchScore().doubleValue() : 0.0,
                a.getExpressionScore() != null ? a.getExpressionScore().doubleValue() : 0.0,
                a.getProjectScore() != null ? a.getProjectScore().doubleValue() : 0.0
        );

        return new ResumeAnalysisResponse(
                a.getOverallScore() != null ? a.getOverallScore().doubleValue() : 0.0,
                detail,
                a.getSummary(),
                fromJson(a.getStrengths(), new TypeReference<>() {}, List.of()),
                fromJson(a.getSuggestions(), new TypeReference<>() {}, List.of()),
                originalText
        );
    }

    private AnalysisItem toAnalysisItem(ResumeAnalysisEntity a) {
        return new AnalysisItem(
                a.getId(),
                a.getOverallScore() != null ? a.getOverallScore().doubleValue() : 0.0,
                a.getContentScore() != null ? a.getContentScore().doubleValue() : 0.0,
                a.getStructureScore() != null ? a.getStructureScore().doubleValue() : 0.0,
                a.getSkillMatchScore() != null ? a.getSkillMatchScore().doubleValue() : 0.0,
                a.getExpressionScore() != null ? a.getExpressionScore().doubleValue() : 0.0,
                a.getProjectScore() != null ? a.getProjectScore().doubleValue() : 0.0,
                a.getSummary(),
                a.getCreatedAt() != null ? a.getCreatedAt().format(DATE_TIME_FORMATTER) : null,
                fromJson(a.getStrengths(), new TypeReference<>() {}, List.of()),
                fromJson(a.getSuggestions(), new TypeReference<>() {}, List.of())
        );
    }

    private String toJson(Object obj, String fallback) {
        try {
            return obj != null ? objectMapper.writeValueAsString(obj) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return fallback;
        }
    }
}
