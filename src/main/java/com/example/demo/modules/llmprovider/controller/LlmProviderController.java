package com.example.demo.modules.llmprovider.controller;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.modules.llmprovider.model.dto.LlmProviderDTO;
import com.example.demo.common.result.Result;
import com.example.demo.modules.llmprovider.service.LlmProviderConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 大模型供应商管理控制器（对齐前端 /api/llm-provider）
 *
 * 权限：类上无注解 → 登录即可（查询、连通性测试）；
 * 新增 / 修改 / 删除 / 切换默认模型 / 重载 / 修改语音配置 → 仅 ADMIN（方法级 @PreAuthorize）。
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
public class LlmProviderController {

    /** 只允许管理员调用的写接口 */
    static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final LlmProviderConfigService configService;

    @GetMapping("/list")
    public Result<List<LlmProviderDTO.ProviderItem>> listProviders() {
        return Result.success(configService.listProviders());
    }

    @GetMapping("/{id}")
    public Result<LlmProviderDTO.ProviderItem> getProvider(@PathVariable String id) {
        return Result.success(configService.getProvider(id));
    }

    @PreAuthorize(ADMIN_ONLY)
    @PostMapping
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<Void> createProvider(@RequestBody LlmProviderDTO.CreateRequest request) {
        configService.createProvider(request);
        return Result.success(null);
    }

    @PreAuthorize(ADMIN_ONLY)
    @PutMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<Void> updateProvider(@PathVariable String id, @RequestBody LlmProviderDTO.UpdateRequest request) {
        configService.updateProvider(id, request);
        return Result.success(null);
    }

    @PreAuthorize(ADMIN_ONLY)
    @DeleteMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<Void> deleteProvider(@PathVariable String id) {
        configService.deleteProvider(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/test")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<LlmProviderDTO.TestResult> testProvider(@PathVariable String id) {
        return Result.success(configService.testProvider(id));
    }

    @PreAuthorize(ADMIN_ONLY)
    @PostMapping("/reload")
    public Result<Void> reloadProviders() {
        configService.reloadProviders();
        return Result.success(null);
    }

    @GetMapping("/default-provider")
    public Result<LlmProviderDTO.DefaultProviderRequest> getDefaultProvider() {
        return Result.success(configService.getDefaultProvider());
    }

    @PreAuthorize(ADMIN_ONLY)
    @PutMapping("/default-provider")
    public Result<Void> updateDefaultProvider(@RequestBody LlmProviderDTO.DefaultProviderRequest request) {
        configService.updateDefaultProvider(request);
        return Result.success(null);
    }

    @PreAuthorize(ADMIN_ONLY)
    @PutMapping("/default-embedding-provider")
    public Result<Void> updateDefaultEmbeddingProvider(@RequestBody LlmProviderDTO.DefaultProviderRequest request) {
        configService.updateDefaultEmbeddingProvider(request);
        return Result.success(null);
    }

    // ===== 🚪 大模型网关 (LLM Gateway) 核心监控与测试接口 =====

    @GetMapping("/gateway/status")
    public Result<LlmProviderDTO.GatewayStatusVO> getGatewayStatus() {
        return Result.success(configService.getGatewayStatus());
    }

    @PostMapping("/gateway/test")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 60)
    public Result<LlmProviderDTO.GatewayTestResponse> testGateway(@RequestBody(required = false) LlmProviderDTO.GatewayTestRequest request) {
        return Result.success(configService.testGateway(request));
    }

    // ===== 语音面试配置 (ASR 语音识别 / TTS 语音合成) =====

    @GetMapping("/voice/asr")
    public Result<LlmProviderDTO.AsrConfig> getAsrConfig() {
        return Result.success(LlmProviderDTO.AsrConfig.builder()
                .model("paraformer-realtime-v2")
                .language("zh")
                .build());
    }

    @PreAuthorize(ADMIN_ONLY)
    @PutMapping("/voice/asr")
    public Result<Void> updateAsrConfig(@RequestBody(required = false) Object request) {
        return Result.success(null);
    }

    @GetMapping("/voice/tts")
    public Result<LlmProviderDTO.TtsConfig> getTtsConfig() {
        return Result.success(LlmProviderDTO.TtsConfig.builder()
                .model("cosyvoice-v1")
                .voice("longxiaochun")
                .build());
    }

    @PreAuthorize(ADMIN_ONLY)
    @PutMapping("/voice/tts")
    public Result<Void> updateTtsConfig(@RequestBody(required = false) Object request) {
        return Result.success(null);
    }
}
