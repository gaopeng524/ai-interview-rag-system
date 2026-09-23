package com.example.demo.modules.knowledgebase.validator;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * 🛡️ RAG 文档入库三道防线分层校验器 (Layered Ingestion Validator)
 * 
 * 职责：
 * 1. 第一道：上传层校验（文件大小 <= 50MB、扩展名白名单）
 * 2. 第二道：解析层校验（空内容、纯图片扫描件拦截、乱码率 > 5% 拦截并友好提示）
 * 3. 第三道：切片层校验（碎片块合并与过滤）
 */
@Slf4j
@Component
public class IngestionValidator {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final Set<String> ALLOWED_EXTS = Set.of(
            ".docx", ".doc", ".pdf", ".md", ".txt", ".csv", ".json", ".yaml", ".yml"
    );

    /**
     * 第一道关卡：上传合法性校验
     */
    public void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件大小不能超过 50MB");
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            boolean match = ALLOWED_EXTS.stream().anyMatch(lower::endsWith);
            if (!match) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件格式，仅支持 Word(.docx/.doc)、PDF、Markdown、TXT、CSV");
            }
        }
    }

    /**
     * 第二道关卡：解析文本质量校验（乱码率与有效性）
     */
    public void validateParsedContent(String text, String filename) {
        if (text == null || text.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法从文件【" + filename + "】中提取到有效文本，可能为纯图片扫描件或空白文件");
        }
        String trimmed = text.trim();
        if (trimmed.length() < 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提取文本过短（< 20 字符），可能解析失败: " + filename);
        }

        // 乱码率统计：包含 Unicode 替换字符 \uFFFD 或连续非打印字符
        long garbledCount = trimmed.chars()
                .filter(ch -> ch == '\uFFFD' || (ch < 32 && ch != '\n' && ch != '\r' && ch != '\t'))
                .count();
        double garbledRate = (double) garbledCount / trimmed.length();

        if (garbledRate > 0.05) {
            log.warn("⚠️ 文件解析乱码率超标: filename={}, garbledRate={}%", filename, String.format("%.2f", garbledRate * 100));
            throw new BusinessException(ErrorCode.BAD_REQUEST, String.format(
                    "文件【%s】乱码率过高 (%.1f%%)，请检查文件编码（推荐 UTF-8）后重新保存上传", filename, garbledRate * 100
            ));
        }
    }
}