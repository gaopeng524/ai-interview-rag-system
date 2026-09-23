package com.example.demo.modules.llmprovider.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 📊 大模型网关·Token 计量与成本统计 Advisor (In-Process Cost Meter)
 */
@Slf4j
public class LlmCostMeterAdvisor implements CallAdvisor, StreamAdvisor {

    private final String providerId;
    private final String modelName;

    // 内存级全局累计 Token 统计
    private static final AtomicLong GLOBAL_PROMPT_TOKENS = new AtomicLong(0);
    private static final AtomicLong GLOBAL_GEN_TOKENS = new AtomicLong(0);
    private static final AtomicLong GLOBAL_TOTAL_COST_MICRO_YUAN = new AtomicLong(0); // 微元 (1元 = 1,000,000微元，防浮点精度丢失)

    public LlmCostMeterAdvisor(String providerId, String modelName) {
        this.providerId = providerId != null ? providerId : "default";
        this.modelName = modelName != null ? modelName : "unknown";
    }

    public static long getGlobalPromptTokens() {
        return GLOBAL_PROMPT_TOKENS.get();
    }

    public static long getGlobalCompletionTokens() {
        return GLOBAL_GEN_TOKENS.get();
    }

    public static long getGlobalTotalTokens() {
        return GLOBAL_PROMPT_TOKENS.get() + GLOBAL_GEN_TOKENS.get();
    }

    public static double getGlobalTotalCostYuan() {
        return GLOBAL_TOTAL_COST_MICRO_YUAN.get() / 1_000_000.0;
    }

    @Override
    public String getName() {
        return "LlmCostMeterAdvisor-" + providerId;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse clientResponse = chain.nextCall(request);
        long costMs = System.currentTimeMillis() - start;

        if (clientResponse != null) {
            recordUsageAndLog(clientResponse.chatResponse(), costMs, false);
        }
        return clientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        return chain.nextStream(request)
                .doOnNext(clientResponse -> {
                    if (clientResponse != null && clientResponse.chatResponse() != null) {
                        ChatResponse cr = clientResponse.chatResponse();
                        if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
                            Usage usage = cr.getMetadata().getUsage();
                            if ((usage.getPromptTokens() != null && usage.getPromptTokens() > 0) ||
                                (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0)) {
                                long costMs = System.currentTimeMillis() - start;
                                recordUsageAndLog(cr, costMs, true);
                            }
                        }
                    }
                });
    }

    private void recordUsageAndLog(ChatResponse chatResponse, long costMs, boolean isStream) {
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return;
        }

        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage == null) {
            return;
        }

        long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0;
        long genTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
        long totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : (promptTokens + genTokens);

        // 🌟 核心防漏算：若 totalTokens > (promptTokens + genTokens)，差值即为深度思考模型 (如 Xiaomi MiMo, DeepSeek-R1) 消耗的 reasoning_tokens
        long actualGenTokens = Math.max(genTokens, totalTokens - promptTokens);
        if (totalTokens < promptTokens + actualGenTokens) {
            totalTokens = promptTokens + actualGenTokens;
        }

        if (promptTokens == 0 && actualGenTokens == 0) {
            return;
        }

        double costYuan = calculateEstimatedCost(promptTokens, actualGenTokens);
        long costMicro = (long) (costYuan * 1_000_000);

        long currentGlobalPrompt = GLOBAL_PROMPT_TOKENS.addAndGet(promptTokens);
        long currentGlobalGen = GLOBAL_GEN_TOKENS.addAndGet(actualGenTokens);
        long currentGlobalCostMicro = GLOBAL_TOTAL_COST_MICRO_YUAN.addAndGet(costMicro);

        log.info("📊 [网关·Token计量] 模式={} | 供应商={} | 模型={} | 耗时={}ms | 输入: {} Tokens, 输出(含思考): {} Tokens (总计: {}) | 本次花费: ￥{} | 全局累计: ￥{}",
                isStream ? "流式SSE" : "同步阻塞",
                providerId,
                modelName,
                costMs,
                promptTokens,
                actualGenTokens,
                totalTokens,
                String.format("%.6f", costYuan),
                String.format("%.4f", currentGlobalCostMicro / 1_000_000.0)
        );
    }

    /**
     * 根据模型名称动态获取每百万 Token 的单价 [输入单价, 输出单价] (单位: 元/M Tokens)
     * 官方定价来源：小米 MiMo / DeepSeek / 通义千问 / 智谱 GLM 官网价格表
     */
    public static double[] getPricePerMillion(String model) {
        if (model == null) return new double[]{1.0, 2.0};
        String lower = model.toLowerCase();

        // 🌟 小米 MiMo 系列官方标准定价 (Domestic Pricing)
        if (lower.contains("mimo-v2.5-pro") || lower.contains("mimo-pro")) {
            return new double[]{3.00, 6.00}; // 输入: ￥3.00/M (未命中缓存), 输出: ￥6.00/M
        } else if (lower.contains("mimo-v2.5") || lower.contains("mimo")) {
            return new double[]{1.00, 2.00}; // 输入: ￥1.00/M, 输出: ￥2.00/M
        }

        // DeepSeek 系列 (DeepSeek-V3 / R1)
        if (lower.contains("deepseek")) {
            return new double[]{1.00, 2.00};
        }

        // 通义千问系列 (Qwen-Turbo / Plus)
        if (lower.contains("qwen") || lower.contains("dashscope")) {
            return new double[]{0.80, 2.00};
        }

        // 智谱 GLM-4-Flash 系列
        if (lower.contains("glm") || lower.contains("flash")) {
            return new double[]{0.10, 0.10};
        }

        return new double[]{1.00, 2.00};
    }

    /**
     * 手动记录特定来源（如流式聊天 SSE、异常兜底或外部调用）消耗的 Token
     */
    public static void recordManualTokens(long promptTokens, long genTokens, String model) {
        if (promptTokens <= 0 && genTokens <= 0) return;
        double[] prices = getPricePerMillion(model);
        double inputPricePerMillion = prices[0];
        double outputPricePerMillion = prices[1];
        double costYuan = (promptTokens * inputPricePerMillion + genTokens * outputPricePerMillion) / 1_000_000.0;
        long costMicro = (long) (costYuan * 1_000_000);

        GLOBAL_PROMPT_TOKENS.addAndGet(promptTokens);
        GLOBAL_GEN_TOKENS.addAndGet(genTokens);
        long currentGlobalCostMicro = GLOBAL_TOTAL_COST_MICRO_YUAN.addAndGet(costMicro);
        log.info("📊 [网关·Token计量] 模式=流式SSE | 模型={} | 输入: {} Tokens, 输出: {} Tokens | 本次花费: ￥{} | 全局累计: ￥{}",
                model != null ? model : "unknown", promptTokens, genTokens,
                String.format("%.6f", costYuan),
                String.format("%.4f", currentGlobalCostMicro / 1_000_000.0));
    }

    private double calculateEstimatedCost(long promptTokens, long genTokens) {
        double[] prices = getPricePerMillion(modelName);
        return (promptTokens * prices[0] + genTokens * prices[1]) / 1_000_000.0;
    }
}