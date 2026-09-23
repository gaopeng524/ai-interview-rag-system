package com.example.demo.modules.resume.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 简历多格式文档极速解析服务（支持 PDF / Word / TXT / Markdown）
 */
@Slf4j
@Service
public class DocumentParseService {

    private final Tika tika = new Tika();

    /**
     * 从上传的文件中提取纯文本
     */
    public String parseContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        log.info("开始解析简历文件: filename={}, size={} bytes", filename, file.getSize());

        // 1. 对于纯文本或 Markdown 文件，直接读取 UTF-8 避免解析器开销
        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            try {
                String text = new String(file.getBytes(), StandardCharsets.UTF_8);
                return sanitizeText(text);
            } catch (Exception e) {
                log.warn("纯文本读取失败，回退到 Tika 解析: {}", e.getMessage());
            }
        }

        // 2. 借助 Apache Tika 解析引擎处理 PDF, DOCX, DOC, RTF 等复杂格式
        try (InputStream is = file.getInputStream()) {
            String parsedText = tika.parseToString(is);
            String cleaned = sanitizeText(parsedText);
            if (cleaned.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "简历解析后内容为空，请检查文件是否损坏或包含仅图片");
            }
            return cleaned;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("解析简历文件失败: filename={}, error={}", filename, e.getMessage(), e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "解析简历文件失败: " + e.getMessage());
        }
    }

    /**
     * 计算文件 SHA-256 哈希值（用于极速秒传与查重）
     */
    public String calculateHash(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(file.getBytes());
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            log.warn("计算文件哈希失败: {}", e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private String sanitizeText(String text) {
        if (text == null) return "";
        // 替换不可见字符与连续多余空行
        return text.replace("\u0000", "")
                .replaceAll("[\\r\\n]{3,}", "\n\n")
                .trim();
    }
}
