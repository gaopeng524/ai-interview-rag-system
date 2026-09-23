package com.example.demo.modules.knowledgebase.service;

import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 辅助工具：将 BigTechDocument.txt 转为带 Word 标题样式和原生表格的 BigTechDocument.docx
 */
public class DocxBenchmarkHelper {

    public static byte[] createBenchmarkDocxWithTable() throws Exception {
        try (InputStream is = DocxBenchmarkHelper.class.getResourceAsStream("/benchmark/BigTechDocument.txt")) {
            String txt = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
            return createBenchmarkDocx(txt);
        }
    }

    public static byte[] createBenchmarkDocx(String txtContent) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 1. 添加文档主标题
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setStyle("Heading1");
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("【大厂高并发与分布式架构核心知识库】");
            titleRun.setBold(true);
            titleRun.setFontSize(16);

            // 2. 插入一个核心技术对比表格（专门供 LlamaParse 和 POI 检验表格转 Markdown 能力）
            XWPFTable table = doc.createTable(4, 3);
            setCell(table.getRow(0).getCell(0), "核心技术组件");
            setCell(table.getRow(0).getCell(1), "底层核心机制");
            setCell(table.getRow(0).getCell(2), "关键高并发/高可用策略");

            setCell(table.getRow(1).getCell(0), "JUC AQS");
            setCell(table.getRow(1).getCell(1), "volatile state + CAS + CLH双向队列");
            setCell(table.getRow(1).getCell(2), "LockSupport.park/unparkSuccessor 挂起与唤醒");

            setCell(table.getRow(2).getCell(0), "Redis 缓存");
            setCell(table.getRow(2).getCell(1), "单线程事件循环 + 内存哈希表");
            setCell(table.getRow(2).getCell(2), "TTL Jitter 抖动防雪崩、Redisson互斥锁防击穿");

            setCell(table.getRow(3).getCell(0), "MySQL InnoDB");
            setCell(table.getRow(3).getCell(1), "Undo Log 版本链 + ReadView");
            setCell(table.getRow(3).getCell(2), "RR 隔离级别快照读 + Next-Key Lock 防幻读");

            // 3. 写入文章各章节正文
            String[] lines = txtContent.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                XWPFParagraph p = doc.createParagraph();
                if (line.startsWith("【第") && line.contains("章")) {
                    p.setStyle("Heading2");
                    XWPFRun r = p.createRun();
                    r.setText(line.trim());
                    r.setBold(true);
                    r.setFontSize(14);
                } else {
                    XWPFRun r = p.createRun();
                    r.setText(line.trim());
                    r.setFontSize(11);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void setCell(XWPFTableCell cell, String text) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
    }
}