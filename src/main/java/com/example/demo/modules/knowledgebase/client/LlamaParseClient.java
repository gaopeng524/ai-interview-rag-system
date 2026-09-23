package com.example.demo.modules.knowledgebase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * 🦙 LlamaParse 官方云端多模态文档解析客户端
 * 
 * 核心能力：
 * 借助 LlamaIndex 视觉多模态大模型，自动攻克 PDF 双栏排版、复杂跨页表格、
 * 架构流程图（Image-to-Text Captioning）与数学公式，一键输出结构化标准 Markdown。
 */
@Slf4j
@Component
public class LlamaParseClient {

    @Value("${app.llamaparse.enabled:true}")
    private boolean enabled;

    @Value("${app.llamaparse.api-key:}")
    private String apiKey;

    @Value("${app.llamaparse.base-url:https://api.cloud.llamaindex.ai/api/parsing}")
    private String baseUrl;

    @Value("${app.llamaparse.timeout-seconds:60}")
    private int timeoutSeconds;

    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断 LlamaParse 是否可用（已启用且配置了 API Key）
     */
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty() && !apiKey.contains("your_api_key");
    }

    /**
     * 提交文件并同步等待获取解析后的标准 Markdown
     *
     * @param fileBytes 文件字节数组
     * @param filename  原始文件名（如 sample.pdf）
     * @return 解析完成的 GitHub Flavored Markdown 文本
     */
    public String parseToMarkdown(byte[] fileBytes, String filename) {
        if (!isAvailable()) {
            throw new IllegalStateException("LlamaParse 未配置或未启用 (app.llamaparse.api-key 为空)");
        }

        log.info("🦙 [LlamaParse] 开始提交云端多模态解析任务: filename={}, size={} bytes", filename, fileBytes.length);

        // 1. 上传文件创建解析 Job
        String jobId = uploadFile(fileBytes, filename);
        log.info("🦙 [LlamaParse] 解析任务已创建，JobId={}, 正在等待视觉大模型解析...", jobId);

        // 2. 轮询等待 Job 完成
        waitForJobCompletion(jobId);

        // 3. 获取并提取最终 Markdown 结果
        String markdown = fetchMarkdownResult(jobId);
        log.info("🦙 [LlamaParse] 解析成功完成！获取到 Markdown 长度: {} 字符", markdown.length());
        return markdown;
    }

    private String uploadFile(byte[] fileBytes, String filename) {
        String uploadUrl = baseUrl + "/upload";

        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "document.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("result_type", "markdown");
        body.add("language", "ch_sim"); // LlamaParse 官方简体中文枚举代码为 ch_sim

        try {
            String response = restClient.post()
                    .uri(uploadUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (root.has("id")) {
                return root.get("id").asText();
            }
            throw new RuntimeException("LlamaParse 返回结果缺少 job id: " + response);
        } catch (Exception e) {
            log.error("🦙 [LlamaParse] 上传文件失败: error={}", e.getMessage(), e);
            throw new RuntimeException("LlamaParse 上传失败: " + e.getMessage(), e);
        }
    }

    private void waitForJobCompletion(String jobId) {
        String statusUrl = baseUrl + "/job/" + jobId;
        long start = System.currentTimeMillis();
        long maxWaitMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - start < maxWaitMillis) {
            try {
                TimeUnit.SECONDS.sleep(1);

                String response = restClient.get()
                        .uri(statusUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                String status = root.has("status") ? root.get("status").asText() : "";

                if ("SUCCESS".equalsIgnoreCase(status)) {
                    return;
                } else if ("ERROR".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                    String errorMsg = root.has("error_message") ? root.get("error_message").asText() : "未知错误";
                    throw new RuntimeException("LlamaParse 解析任务执行失败: " + errorMsg);
                }
                // PENDING / PROCESSING 继续轮询
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LlamaParse 轮询被中断", e);
            } catch (Exception e) {
                if (e.getMessage().contains("执行失败")) {
                    throw (RuntimeException) e;
                }
                log.warn("🦙 [LlamaParse] 轮询状态异常重试中: {}", e.getMessage());
            }
        }

        throw new RuntimeException("LlamaParse 解析超时（超过 " + timeoutSeconds + " 秒未完成）");
    }

    private String fetchMarkdownResult(String jobId) {
        String resultUrl = baseUrl + "/job/" + jobId + "/result/markdown";

        try {
            String response = restClient.get()
                    .uri(resultUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new RuntimeException("LlamaParse 返回内容为空");
            }

            // 如果返回的是 JSON 封装格式: {"markdown": "..."}
            if (response.trim().startsWith("{")) {
                try {
                    JsonNode node = objectMapper.readTree(response);
                    if (node.has("markdown")) {
                        return node.get("markdown").asText();
                    }
                } catch (Exception ignored) {}
            }

            return response;
        } catch (Exception e) {
            log.error("🦙 [LlamaParse] 获取 Markdown 结果失败: error={}", e.getMessage(), e);
            throw new RuntimeException("获取 LlamaParse 结果失败: " + e.getMessage(), e);
        }
    }
}