package com.example.demo.modules.knowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文件解析服务
 * 支持 Word（.docx / .doc 原生 POI 解析）、PDF（Tika/PDFBox）、TXT、Markdown 等纯文本提取，
 * 并支持 LlamaParse 云端多模态视觉大模型智能解析（自动攻克双栏排版、跨页表格与架构图）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileParseService {

    private final Tika tika = new Tika();
    private final com.example.demo.modules.knowledgebase.client.LlamaParseClient llamaParseClient;

    /**
     * 解析文件内容，提取纯文本 / 标准 Markdown
     *
     * @param file 上传的文件
     * @return 提取的文本内容
     */
    public String parseFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        long size = file.getSize();
        String contentType = file.getContentType();

        log.info("开始解析文件: filename={}, size={} bytes, contentType={}", filename, size, contentType);

        try {
            String lowerName = (filename != null) ? filename.toLowerCase() : "";

            // 🌟 0. 高级多模态：若配置了 LlamaParse API Key，PDF/Word 优先走 LlamaParse 视觉大模型解析！
            if (llamaParseClient.isAvailable() && (lowerName.endsWith(".pdf") || lowerName.endsWith(".docx"))) {
                try {
                    log.info("🦙 触发 LlamaParse 云端多模态解析: filename={}", filename);
                    String md = llamaParseClient.parseToMarkdown(file.getBytes(), filename);
                    if (md != null && !md.isBlank()) {
                        log.info("🦙 LlamaParse 解析成功，输出标准 Markdown 长度: {}", md.length());
                        return md.trim();
                    }
                } catch (Exception e) {
                    log.warn("⚠️ LlamaParse 云端解析异常，自动回退本地原生引擎: error={}", e.getMessage());
                }
            }

            String content = "";

            // 1. .docx 文件：直接使用 Apache POI 原生解析
            if (lowerName.endsWith(".docx")) {
                content = parseDocx(file);
                log.info("POI 原生解析 docx 完成: filename={}, 文本长度={}", filename, content.length());
            }
            // 2. .doc 文件（Word 97-2003）：使用 HWPFDocument 原生解析
            else if (lowerName.endsWith(".doc")) {
                content = parseDoc(file);
                log.info("POI 原生解析 doc 完成: filename={}, 文本长度={}", filename, content.length());
            }
            // 3. 纯文本文件（txt / md / csv / json / yaml 等）：直接读取并做编码自适应
            else if (isTextFile(contentType, lowerName)) {
                byte[] bytes = file.getBytes();
                content = new String(bytes, StandardCharsets.UTF_8);
                if (content.contains("\uFFFD")) {
                    try {
                        String gbkContent = new String(bytes, java.nio.charset.Charset.forName("GBK"));
                        if (!gbkContent.contains("\uFFFD")) {
                            content = gbkContent;
                        }
                    } catch (Exception ignored) {}
                }
                log.info("纯文本文件读取完成: filename={}, 文本长度={}", filename, content.length());
            }
            // 4. PDF 及其他复杂二进制文件：调用 Apache Tika 引擎
            else {
                Metadata metadata = new Metadata();
                if (filename != null && !filename.isBlank()) {
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
                }
                if (contentType != null && !contentType.isBlank()) {
                    metadata.set(Metadata.CONTENT_TYPE, contentType);
                }

                try (InputStream inputStream = file.getInputStream()) {
                    content = tika.parseToString(inputStream, metadata);
                }
                log.info("Tika 引擎解析完成: filename={}, 文本长度={}", filename, content != null ? content.length() : 0);
            }

            return content != null ? content.trim() : "";
        } catch (Exception e) {
            log.error("文件解析失败: filename={}, error={}", filename, e.getMessage(), e);
            throw new RuntimeException("文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 原生 POI 解析 .docx 文件（按自然段落与表格顺序提取，表格 100% 结构化转 Markdown Table）
     */
    private String parseDocx(MultipartFile file) throws Exception {
        try (InputStream is = file.getInputStream();
             XWPFDocument doc = new XWPFDocument(is)) {

            StringBuilder sb = new StringBuilder();

            for (org.apache.poi.xwpf.usermodel.IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (text != null && !text.isBlank()) {
                        String style = p.getStyle();
                        if (style != null && style.toLowerCase().contains("heading 1")) {
                            sb.append("\n# ").append(text.trim()).append("\n\n");
                        } else if (style != null && style.toLowerCase().contains("heading 2")) {
                            sb.append("\n## ").append(text.trim()).append("\n\n");
                        } else {
                            sb.append(text.trim()).append("\n");
                        }
                    }
                } else if (element instanceof XWPFTable table) {
                    sb.append("\n").append(formatTableToMarkdown(table)).append("\n\n");
                }
            }

            return sb.toString().trim();
        }
    }

    private String formatTableToMarkdown(XWPFTable table) {
        if (table == null || table.getRows().isEmpty()) return "";
        StringBuilder md = new StringBuilder("\n");
        List<XWPFTableRow> rows = table.getRows();

        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            md.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText().replaceAll("\\s+", " ").trim();
                md.append(text).append(" | ");
            }
            md.append("\n");

            // 自动补齐 Markdown 表头分隔线
            if (r == 0) {
                md.append("|").append(" --- |".repeat(row.getTableCells().size())).append("\n");
            }
        }
        return md.toString();
    }

    /**
     * 原生 POI 解析 .doc 文件（Word 97-2003 格式）
     */
    private String parseDoc(MultipartFile file) throws Exception {
        try (InputStream is = file.getInputStream();
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * 判断是否是纯文本文件
     */
    private boolean isTextFile(String contentType, String filename) {
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".pdf")) {
                return false;
            }
            if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                return true;
            }
        }
        return contentType != null && contentType.startsWith("text/");
    }

    /**
     * 检查文件类型是否支持
     */
    public boolean isSupportedFileType(String contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.equals("application/pdf")
                || contentType.equals("application/msword")
                || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || contentType.equals("text/plain")
                || contentType.equals("text/markdown");
    }
}
