package com.example.demo.common.util;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.resume.model.dto.ResumeDTO.ResumeAnalysisResponse;
import com.example.demo.modules.resume.model.dto.ResumeDTO.Suggestion;
import com.example.demo.modules.resume.model.entity.ResumeEntity;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;

/**
 * 工业级 PDF 导出服务（支持中文 TrueType 字体与专业排版）
 */
@Slf4j
@Service
public class PdfExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DeviceRgb HEADER_COLOR = new DeviceRgb(41, 128, 185);
    private static final DeviceRgb SECTION_COLOR = new DeviceRgb(52, 73, 94);

    /**
     * 导出简历分析评估报告为标准 PDF 字节流
     */
    public byte[] exportResumeAnalysisPdf(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document document = new Document(pdfDoc);

            // 1. 加载中文字体
            PdfFont font = loadChineseFont();
            document.setFont(font);

            // 2. 报告主标题
            Paragraph title = new Paragraph("简历智能深度诊断与优化建议报告")
                    .setFontSize(22)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(HEADER_COLOR);
            document.add(title);
            document.add(new Paragraph("\n"));

            // 3. 基本信息区块
            document.add(createSectionTitle(font, "一、基本信息"));
            document.add(new Paragraph("• 简历文件名: " + sanitizeText(resume.getFilename())));
            document.add(new Paragraph("• 上传时间: " + (resume.getCreatedAt() != null ? DATE_FORMAT.format(resume.getCreatedAt()) : "未知")));
            document.add(new Paragraph("• 文件大小: " + formatFileSize(resume.getFileSize())));

            // 4. 综合评分
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle(font, "二、综合评分与诊断"));
            Paragraph scoreP = new Paragraph("综合得分: " + (analysis.overallScore() > 0 ? analysis.overallScore() : resume.getLatestScore()) + " / 100 分")
                    .setFontSize(16)
                    .setBold()
                    .setFontColor(getScoreColor(analysis.overallScore()));
            document.add(scoreP);

            // 5. 五维评分明细表格
            if (analysis.scoreDetail() != null) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle(font, "三、五维技术能力维度评分"));

                Table scoreTable = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2})).useAllAvailableWidth();
                scoreTable.addHeaderCell(createHeaderCell(font, "评估维度"));
                scoreTable.addHeaderCell(createHeaderCell(font, "候选人得分"));
                scoreTable.addHeaderCell(createHeaderCell(font, "满分基准"));

                addScoreRow(scoreTable, font, "内容完整性 (Content)", analysis.scoreDetail().contentScore(), 25);
                addScoreRow(scoreTable, font, "结构清晰度 (Structure)", analysis.scoreDetail().structureScore(), 20);
                addScoreRow(scoreTable, font, "技能匹配度 (Skill Match)", analysis.scoreDetail().skillMatchScore(), 25);
                addScoreRow(scoreTable, font, "表达专业性 (Expression)", analysis.scoreDetail().expressionScore(), 15);
                addScoreRow(scoreTable, font, "项目经验深度 (Project)", analysis.scoreDetail().projectScore(), 15);
                document.add(scoreTable);
            }

            // 6. 简历综合摘要
            if (analysis.summary() != null && !analysis.summary().isBlank()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle(font, "四、综合诊断总结"));
                document.add(new Paragraph(sanitizeText(analysis.summary())).setItalic());
            }

            // 7. 核心技术亮点
            if (analysis.strengths() != null && !analysis.strengths().isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle(font, "五、核心技术优势亮点"));
                for (String strength : analysis.strengths()) {
                    document.add(new Paragraph("✔ " + sanitizeText(strength)).setFontColor(new DeviceRgb(39, 174, 96)));
                }
            }

            // 8. STAR 法则优化建议
            if (analysis.suggestions() != null && !analysis.suggestions().isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle(font, "六、STAR 法则润色与优化改进建议"));
                for (Suggestion sug : analysis.suggestions()) {
                    Paragraph sugTitle = new Paragraph("【" + sug.priority() + "优先级 - " + sug.category() + "】" + sanitizeText(sug.issue()))
                            .setBold()
                            .setFontColor(getPriorityColor(sug.priority()));
                    document.add(sugTitle);
                    document.add(new Paragraph("👉 优化建议与示范: " + sanitizeText(sug.recommendation())));
                    document.add(new Paragraph(" "));
                }
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("生成简历分析 PDF 失败: resumeId={}, error={}", resume.getId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成 PDF 失败: " + e.getMessage());
        }
    }

    // ==================== 字体与格式排版辅助 ====================

    private PdfFont loadChineseFont() {
        try (InputStream fontStream = getClass().getClassLoader().getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")) {
            if (fontStream != null) {
                byte[] fontBytes = fontStream.readAllBytes();
                return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED);
            }
        } catch (Exception e) {
            log.warn("加载项目内嵌中文字体失败，尝试备用字体: {}", e.getMessage());
        }
        try {
            return PdfFontFactory.createFont("STSong-Light", "UniGB-UCS2-H", EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.error("加载备用字体失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "中文字体库加载失败");
        }
    }

    private Paragraph createSectionTitle(PdfFont font, String text) {
        return new Paragraph(text)
                .setFont(font)
                .setFontSize(14)
                .setBold()
                .setFontColor(SECTION_COLOR)
                .setMarginBottom(5);
    }

    private Cell createHeaderCell(PdfFont font, String text) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setBold().setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(HEADER_COLOR)
                .setTextAlignment(TextAlignment.CENTER);
    }

    private void addScoreRow(Table table, PdfFont font, String dimension, double score, double fullMark) {
        table.addCell(new Cell().add(new Paragraph(dimension).setFont(font)));
        table.addCell(new Cell().add(new Paragraph(String.format("%.1f 分", score)).setFont(font)).setTextAlignment(TextAlignment.CENTER));
        table.addCell(new Cell().add(new Paragraph(String.format("%.0f 分", fullMark)).setFont(font)).setTextAlignment(TextAlignment.CENTER));
    }

    private DeviceRgb getScoreColor(double score) {
        if (score >= 85) return new DeviceRgb(39, 174, 96);
        if (score >= 70) return new DeviceRgb(243, 156, 18);
        return new DeviceRgb(231, 76, 60);
    }

    private DeviceRgb getPriorityColor(String priority) {
        if ("高".equals(priority)) return new DeviceRgb(231, 76, 60);
        if ("中".equals(priority)) return new DeviceRgb(230, 126, 34);
        return new DeviceRgb(41, 128, 185);
    }

    private String sanitizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }

    private String formatFileSize(Long bytes) {
        if (bytes == null || bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
