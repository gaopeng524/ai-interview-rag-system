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
    private final com.example.demo.modules.knowledgebase.mapper.KnowledgeBaseQuestionMapper questionMapper;

    /**
     * 添加知识（手动输入纯文本）：元数据入库并异步推入 Redis Stream
     */
    public Long addKnowledge(KnowledgeBaseDTO.AddRequest request, Long userId) {
        requireUser(userId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称不能为空");
        }
        String name = request.name().trim();
        String content = request.content() != null ? request.content().trim() : "";
        String category = request.category() != null ? request.category().trim() : "默认分类";

        ingestionValidator.validateParsedContent(content, name);

        log.info("添加知识: name={}, category={}, length={}", name, category, content.length());

        KnowledgeBase kb = new KnowledgeBase();
        kb.setUserId(userId);
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
    public KnowledgeBaseDTO.UploadResponse uploadKnowledgeBase(MultipartFile file, String name, String category, Long userId) {
        requireUser(userId);
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

        // 只在当前用户自己的知识库里去重
        KnowledgeBase existing = knowledgeBaseMapper.findByFileHash(userId, fileHash);
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
        kb.setUserId(userId);
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

    // ==================== 归属校验 ====================

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问知识库");
        }
    }

    /**
     * 知识库归属校验：按 id 访问知识库（及其考题）的所有用户入口都必须经过这里。
     * userId 为空、知识库没有归属人或归属人不是当前用户，一律 403；先判断身份再查库。
     */
    public KnowledgeBase requireOwned(Long id, Long userId) {
        requireUser(userId);
        KnowledgeBase kb = knowledgeBaseMapper.findById(id);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
        }
        if (!userId.equals(kb.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        return kb;
    }

    /**
     * 把请求里的知识库范围解析成"当前用户实际可以检索的范围"：
     * <ul>
     *   <li>指定了知识库：必须全部属于当前用户，否则 403；</li>
     *   <li>没指定：限定为当前用户名下的全部知识库（原来会检索到所有人的内容）。</li>
     * </ul>
     * 返回空列表表示该用户没有任何可检索的知识库，调用方应直接返回空结果，不能再做全局检索。
     */
    public List<Long> resolveSearchScope(List<Long> requestedIds, Long userId) {
        requireUser(userId);
        List<Long> owned = knowledgeBaseMapper.findIdsByUser(userId);
        if (requestedIds == null || requestedIds.isEmpty()) {
            return owned;
        }
        if (!new java.util.HashSet<>(owned).containsAll(requestedIds)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问所选知识库");
        }
        return requestedIds;
    }

    /**
     * 从一组知识库 id 中筛出属于当前用户的（已删除或不属于自己的静默丢弃）。
     * 用于会话里已经保存的关联关系：知识库被删除后，会话不应该因此报错。
     */
    public List<Long> filterOwned(List<Long> knowledgeBaseIds, Long userId) {
        if (userId == null || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        java.util.Set<Long> owned = new java.util.HashSet<>(knowledgeBaseMapper.findIdsByUser(userId));
        return knowledgeBaseIds.stream().filter(owned::contains).toList();
    }

    // ==================== 基础元数据 CRUD（均限定在当前用户名下） ====================

    public List<KnowledgeBase> findAll(Long userId) {
        requireUser(userId);
        return knowledgeBaseMapper.findAllByUser(userId);
    }

    public KnowledgeBase findById(Long id, Long userId) {
        return requireOwned(id, userId);
    }

    /**
     * 删除知识库，同时清理它的向量切片和提炼出的考题。
     * 原来只删元数据，向量留在库里，仍会被"不指定知识库"的检索召回。
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteById(Long id, Long userId) {
        requireOwned(id, userId);
        questionMapper.deleteByKnowledgeBaseId(id);
        knowledgeBaseMapper.deleteById(id);
        deleteVectorsOf(id);
        searchService.evictRagCache();
        log.info("已删除知识库及其向量切片与考题: id={}", id);
    }

    public List<KnowledgeBase> searchByName(String keyword, Long userId) {
        if (keyword == null || keyword.isBlank()) return findAll(userId);
        requireUser(userId);
        return knowledgeBaseMapper.searchByName(userId, keyword.trim());
    }

    public List<String> getAllCategories(Long userId) {
        requireUser(userId);
        return knowledgeBaseMapper.findAllCategories(userId);
    }

    public Map<String, Object> getStatistics(Long userId) {
        requireUser(userId);
        return Map.of(
                "totalCount", knowledgeBaseMapper.countAll(userId),
                "completedCount", knowledgeBaseMapper.countByVectorStatus(userId, "COMPLETED"),
                "processingCount", knowledgeBaseMapper.countByVectorStatus(userId, "PROCESSING"),
                "failedCount", knowledgeBaseMapper.countByVectorStatus(userId, "FAILED"),
                "pendingCount", knowledgeBaseMapper.countByVectorStatus(userId, "PENDING"),
                "totalQuestionCount", 0,
                "totalAccessCount", 0
        );
    }

    // ==================== 面向用户的检索与问答（先解析可访问范围） ====================

    private static final String NO_KB_ANSWER = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";

    /** 在当前用户可访问的知识库范围内检索；范围为空时返回空结果 */
    public List<Document> search(String query, List<Long> knowledgeBaseIds, int topK, Long userId) {
        List<Long> scope = resolveSearchScope(knowledgeBaseIds, userId);
        return scope.isEmpty() ? List.of() : searchService.search(query, scope, topK);
    }

    public List<KnowledgeBaseDTO.SearchResultItem> searchSemantic(KnowledgeBaseDTO.SearchRequest request, Long userId) {
        if (request == null) return List.of();
        List<Long> scope = resolveSearchScope(request.knowledgeBaseIds(), userId);
        if (scope.isEmpty()) return List.of();
        return searchService.searchSemantic(new KnowledgeBaseDTO.SearchRequest(request.query(), scope, request.topK()));
    }

    public KnowledgeBaseDTO.QueryResponse queryKnowledgeBase(KnowledgeBaseDTO.QueryRequest request, Long userId) {
        if (request == null) return searchService.queryKnowledgeBase(null);
        List<Long> scope = resolveSearchScope(request.knowledgeBaseIds(), userId);
        if (scope.isEmpty()) return new KnowledgeBaseDTO.QueryResponse(NO_KB_ANSWER, 0L, "");
        return searchService.queryKnowledgeBase(new KnowledgeBaseDTO.QueryRequest(scope, request.question()));
    }

    public Flux<String> queryKnowledgeBaseStream(KnowledgeBaseDTO.QueryRequest request, Long userId) {
        if (request == null) return searchService.queryKnowledgeBaseStream(null);
        List<Long> scope = resolveSearchScope(request.knowledgeBaseIds(), userId);
        if (scope.isEmpty()) return Flux.just(NO_KB_ANSWER);
        return searchService.queryKnowledgeBaseStream(new KnowledgeBaseDTO.QueryRequest(scope, request.question()));
    }

    // ==================== 内部调用（后台任务，知识库 id 已由调用方确定，不做归属校验） ====================

    /** 仅供后台出题任务等内部流程使用，request 中必须显式指定知识库 */
    public List<KnowledgeBaseDTO.SearchResultItem> searchSemanticInternal(KnowledgeBaseDTO.SearchRequest request) {
        return searchService.searchSemantic(request);
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