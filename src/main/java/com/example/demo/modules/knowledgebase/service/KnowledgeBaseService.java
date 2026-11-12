package com.example.demo.modules.knowledgebase.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.modules.knowledgebase.async.VectorizeStreamProducer;
import com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理业务服务
 * 职责：知识库元数据 CRUD、文件解析入库、文本切片向量化任务分发
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final VectorStore vectorStore;
    private final FileParseService fileParseService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final KnowledgeBaseSearchService searchService;
    private final com.example.demo.modules.knowledgebase.validator.IngestionValidator ingestionValidator;
    private final com.example.demo.modules.rag.chunker.ParentChildChunker parentChildChunker;

    /**
     * 添加知识（手动输入纯文本）：元数据入库并异步推入 Redis Stream
     */
    public Long addKnowledge(KnowledgeBaseDTO.AddRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能为空");
        }
        String name = request.name().trim();
        String content = request.content() != null ? request.content().trim() : "";
        String category = request.category() != null ? request.category().trim() : "默认分类";

        ingestionValidator.validateParsedContent(content, name);

        log.info("添加知识: name={}, category={}, length={}", name, category, content.length());

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setOriginalFilename("manual-input.txt");
        kb.setFileSize((long) content.length());
        kb.setContentType("text/plain");
        kb.setCategory(category);
        kb.setFileHash(calculateSha256(content.getBytes()));
        kb.setVectorStatus("PROCESSING");
        knowledgeBaseMapper.insert(kb);

        vectorizeStreamProducer.sendVectorizeTask(kb.getId(), content);
        return kb.getId();
    }

    /**
     * 上传知识库文件并解析：计算 Hash 自动秒级去重 + 异步推入向量化队列
     */
    public KnowledgeBaseDTO.UploadResponse uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 第一道关卡：上传合法性校验（大小限制、扩展名白名单）
        ingestionValidator.validateUpload(file);

        String originalFilename = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            name = originalFilename != null ? originalFilename : "未命名文件";
        }
        if (category == null || category.isBlank()) {
            category = "未分类";
        }

        // 2. 文件 SHA-256 极速秒传排重检查
        String fileHash;
        try {
            fileHash = calculateSha256(file.getBytes());
        } catch (Exception e) {
            fileHash = String.valueOf(file.hashCode());
        }

        KnowledgeBase existing = knowledgeBaseMapper.findByFileHash(fileHash);
        if (existing != null) {
            log.info("文件已存在，秒级去重命中: existingId={}", existing.getId());
            return new KnowledgeBaseDTO.UploadResponse(
                    existing.getId(), existing.getName(), existing.getCategory(), existing.getFileSize(), "文件已存在（已自动去重）"
            );
        }

        // 3. 提取文件正文（支持 LlamaParse 视觉大模型与 POI Markdown 表格）
        String content = fileParseService.parseFile(file);

        // 4. 第二道关卡：解析内容质量校验（空内容、乱码率拦截）
        ingestionValidator.validateParsedContent(content, originalFilename);

        // 5. 元数据入库
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setOriginalFilename(originalFilename);
        kb.setFileSize(file.getSize());
        kb.setContentType(file.getContentType());
        kb.setFileHash(fileHash);
        kb.setCategory(category);
        kb.setVectorStatus("PROCESSING");
        knowledgeBaseMapper.insert(kb);

        // 6. 异步推入 Redis Stream 任务队列
        vectorizeStreamProducer.sendVectorizeTask(kb.getId(), content);
        log.info("文件向量化任务已推入异步队列: id={}", kb.getId());

        return new KnowledgeBaseDTO.UploadResponse(
                kb.getId(), kb.getName(), kb.getCategory(), kb.getFileSize(), "文件上传成功，正在后台异步向量化处理中"
        );
    }

    /**
     * 供 Redis Stream 消费者调用的分块向量化方法
     */
    public void processVectorizeTask(Long kbId, String content) {
        log.info("开始处理后台向量化任务: kbId={}", kbId);
        // 幂等：先删除该知识库已有的全部向量切片，再整体重写。
        // 消息可能因为消费者崩溃被回收重放，上一次可能已经写入了一部分批次；
        // "按 kb_id 先删后写" 保证无论执行几次，最终都只有一份切片，不会出现重复向量。
        deleteVectorsOf(kbId);
        int chunkCount = vectorizeAndStore(kbId, content);
        knowledgeBaseMapper.updateVectorStatus(kbId, "COMPLETED", null, chunkCount);
        searchService.evictRagCache();
        log.info("后台向量化处理完成: kbId={}, chunkCount={}", kbId, chunkCount);
    }

    void deleteVectorsOf(Long kbId) {
        vectorStore.delete(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("kb_id", String.valueOf(kbId)).build());
    }

    private int vectorizeAndStore(Long knowledgeBaseId, String content) {
        if (content == null || content.trim().isEmpty()) return 0;

        KnowledgeBase kb = knowledgeBaseMapper.findById(knowledgeBaseId);
        String docName = kb != null ? kb.getName() : "知识库文档";
        String category = kb != null ? kb.getCategory() : "默认";

        // 🌟 核心升级：采用父子块级联切片 (Parent: 750 Tokens 上下文, Child: 180 Tokens 向量检索) + 面包屑注入
        List<Document> chunks = parentChildChunker.chunkToParentChildDocuments(content, docName, category, knowledgeBaseId);
        if (chunks.isEmpty()) {
            String idStr = String.valueOf(knowledgeBaseId);
            chunks = List.of(new Document(content.trim(), Map.of("kb_id", idStr, "knowledge_base_id", idStr)));
        }

        // 分批写入数据库，每批最多 6 条，严格规避 DashScope 10 条限制
        int batchSize = 6;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            vectorStore.accept(batch);
        }
        return chunks.size();
    }

    // ==================== 基础元数据 CRUD ====================

    public List<KnowledgeBase> findAll() {
        return knowledgeBaseMapper.findAll();
    }

    public KnowledgeBase findById(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.findById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
        }
        return kb;
    }

    public List<KnowledgeBase> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return knowledgeBaseMapper.findByIds(ids);
    }

    public void deleteById(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.findById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "要删除的知识库不存在");
        }
        knowledgeBaseMapper.deleteById(id);
        searchService.evictRagCache();
        log.info("已删除知识库: id={}", id);
    }

    public List<KnowledgeBase> searchByName(String keyword) {
        if (keyword == null || keyword.isBlank()) return findAll();
        return knowledgeBaseMapper.searchByName(keyword.trim());
    }

    public List<String> getAllCategories() {
        return knowledgeBaseMapper.findAllCategories();
    }

    public Map<String, Object> getStatistics() {
        return Map.of(
                "totalCount", knowledgeBaseMapper.countAll(),
                "completedCount", knowledgeBaseMapper.countByVectorStatus("COMPLETED"),
                "processingCount", knowledgeBaseMapper.countByVectorStatus("PROCESSING"),
                "failedCount", knowledgeBaseMapper.countByVectorStatus("FAILED"),
                "pendingCount", knowledgeBaseMapper.countByVectorStatus("PENDING"),
                "totalQuestionCount", 0,
                "totalAccessCount", 0
        );
    }

    // ==================== 统一委派给 KnowledgeBaseSearchService ====================

    public List<Document> search(String query, int topK) {
        return searchService.search(query, topK);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds) {
        return searchService.search(query, knowledgeBaseIds);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds, int topK) {
        return searchService.search(query, knowledgeBaseIds, topK);
    }

    public List<Document> search(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        return searchService.search(query, knowledgeBaseIds, topK, minScore);
    }

    public List<KnowledgeBaseDTO.SearchResultItem> searchSemantic(KnowledgeBaseDTO.SearchRequest request) {
        return searchService.searchSemantic(request);
    }

    public KnowledgeBaseDTO.QueryResponse queryKnowledgeBase(KnowledgeBaseDTO.QueryRequest request) {
        return searchService.queryKnowledgeBase(request);
    }

    public Flux<String> queryKnowledgeBaseStream(KnowledgeBaseDTO.QueryRequest request) {
        return searchService.queryKnowledgeBaseStream(request);
    }

    public String ask(String question, List<Long> knowledgeBaseIds) {
        return searchService.ask(question, knowledgeBaseIds);
    }

    public void evictRagCache() {
        searchService.evictRagCache();
    }

    private String calculateSha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(bytes));
        }
    }
}