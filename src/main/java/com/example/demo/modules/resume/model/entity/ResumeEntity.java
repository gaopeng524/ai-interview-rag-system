package com.example.demo.modules.resume.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 简历基础信息实体表 (resumes)
 */
@Data
public class ResumeEntity {

    private Long id;
    private Long userId;
    private String filename;
    private Long fileSize;
    private String fileHash;
    private String contentType;
    private String resumeText;
    private String analyzeStatus; // PENDING | PROCESSING | COMPLETED | FAILED
    private String analyzeError;
    private BigDecimal latestScore;
    private Integer accessCount;
    private Integer interviewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ResumeEntity create(Long userId,
                                      String filename,
                                      long fileSize,
                                      String fileHash,
                                      String contentType,
                                      String resumeText) {
        ResumeEntity resume = new ResumeEntity();
        resume.setUserId(userId);
        resume.setFilename(filename);
        resume.setFileSize(fileSize);
        resume.setFileHash(fileHash);
        resume.setContentType(contentType);
        resume.setResumeText(resumeText);
        resume.setAnalyzeStatus("PROCESSING");
        resume.setAccessCount(1);
        resume.setInterviewCount(0);
        return resume;
    }
}
