package com.example.demo.modules.knowledgebase.controller;

import com.example.demo.modules.knowledgebase.model.dto.KnowledgeBaseDTO;
import com.example.demo.modules.knowledgebase.model.entity.KnowledgeBase;
import com.example.demo.common.result.Result;
import com.example.demo.common.security.CurrentUser;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器（薄 Controller 设计）：
 * 职责仅限于参数接收、路由映射、调用 Service 层和封装 Result 统一返回。
 */
@RestController
@RequestMapping("/api/knowledgebase")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/list")
    public Result<List<KnowledgeBase>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(knowledgeBaseService.findAll(CurrentUser.requireId(principal)));
    }

    /**
     * 根据ID获取知识库详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeBase> detail(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(knowledgeBaseService.findById(id, CurrentUser.requireId(principal)));
    }

    /**
     * 添加知识（手动输入文本）
     */
    @PostMapping
    @com.example.demo.common.annotation.RateLimit(dimension = com.example.demo.common.annotation.RateLimit.Dimension.IP, count = 10, interval = 60, message = "添加知识过于频繁，请稍后再试")
    public Result<Map<String, Object>> add(@RequestBody KnowledgeBaseDTO.AddRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        Long id = knowledgeBaseService.addKnowledge(request, CurrentUser.requireId(principal));
        return Result.success(Map.of("id", id, "message", "添加成功"));
    }

    /**
     * 添加知识（文件上传并解析向量化）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @com.example.demo.common.annotation.RateLimit(dimension = com.example.demo.common.annotation.RateLimit.Dimension.IP, count = 5, interval = 60, message = "文件上传过于频繁，请稍后再试")
    @com.example.demo.common.annotation.RateLimit(dimension = com.example.demo.common.annotation.RateLimit.Dimension.GLOBAL, count = 20, interval = 60, message = "系统文件处理繁忙，请稍后再试")
    public Result<KnowledgeBaseDTO.UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal UserPrincipal principal) {

        KnowledgeBaseDTO.UploadResponse response = knowledgeBaseService.uploadKnowledgeBase(
                file, name, category, CurrentUser.requireId(principal));
        return Result.success(response);
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        knowledgeBaseService.deleteById(id, CurrentUser.requireId(principal));
        return Result.success(null);
    }

    /**
     * 搜索知识库（按名称关键词）
     */
    @GetMapping("/search")
    public Result<List<KnowledgeBase>> search(@RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(knowledgeBaseService.searchByName(keyword, CurrentUser.requireId(principal)));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/categories")
    public Result<List<String>> getAllCategories(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(knowledgeBaseService.getAllCategories(CurrentUser.requireId(principal)));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStatistics(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(knowledgeBaseService.getStatistics(CurrentUser.requireId(principal)));
    }

    /**
     * 语义搜索（向量相似度检索）
     */
    @PostMapping("/search")
    public Result<List<KnowledgeBaseDTO.SearchResultItem>> searchSemantic(@RequestBody KnowledgeBaseDTO.SearchRequest request,
                                                                        @AuthenticationPrincipal UserPrincipal principal) {
        List<KnowledgeBaseDTO.SearchResultItem> results =
                knowledgeBaseService.searchSemantic(request, CurrentUser.requireId(principal));
        return Result.success(results);
    }

    /**
     * RAG 问答（同步非流式）
     */
    @PostMapping("/query")
    public Result<KnowledgeBaseDTO.QueryResponse> query(@RequestBody KnowledgeBaseDTO.QueryRequest request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        KnowledgeBaseDTO.QueryResponse response =
                knowledgeBaseService.queryKnowledgeBase(request, CurrentUser.requireId(principal));
        return Result.success(response);
    }

    /**
     * RAG 问答（流式 SSE）
     */
    @PostMapping(value = "/query/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> queryStream(@RequestBody KnowledgeBaseDTO.QueryRequest request,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return knowledgeBaseService.queryKnowledgeBaseStream(request, CurrentUser.requireId(principal));
    }

}
