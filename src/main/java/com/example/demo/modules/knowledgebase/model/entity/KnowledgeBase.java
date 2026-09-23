package com.example.demo.modules.knowledgebase.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {
    private Long id;
    private String name;              // 知识库名称
    private String originalFilename;  // 原始文件名
    private Long fileSize;            // 文件大小
    private String contentType;       // 文件类型
    private String fileHash;          // 文件哈希（用于去重）
    private String category;          // 分类
    private String vectorStatus;      // 向量化状态：PENDING, COMPLETED, FAILED
    private String vectorError;       // 向量化错误信息
    private Integer chunkCount;       // 分块数量
    private LocalDateTime uploadedAt; // 上传时间
    private LocalDateTime updatedAt;  // 更新时间
}
