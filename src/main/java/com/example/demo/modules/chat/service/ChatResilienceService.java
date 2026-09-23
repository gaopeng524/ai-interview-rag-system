package com.example.demo.modules.chat.service;

import com.example.demo.modules.llmprovider.core.LlmProviderProperties.ProviderConfig;
import com.example.demo.modules.llmprovider.core.LlmProviderRegistry;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.example.demo.common.ai.trace.TraceManager;
import com.example.demo.modules.llmprovider.core.LlmCostMeterAdvisor;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 大模型调用容错服务（流式：超时/重试/降级 + 深度思考链路实时透传）。
 */
@Slf4j
@Service
public class ChatResilienceService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000L;
    private static final String FALLBACK_RESPONSE = "AI 服务暂时不可用，请稍后重试。";
    private static final String STREAM_INTERRUPTED_RESPONSE = "生成已中断，请重试。";

    private final LlmProviderRegistry llmProviderRegistry;
    private final ToolCallbackProvider weatherToolProvider;
    private final long retryDelayMs;

    @Autowired
    public ChatResilienceService(LlmProviderRegistry llmProviderRegistry,
                                 ToolCallbackProvider weatherToolProvider) {
        this(llmProviderRegistry, weatherToolProvider, DEFAULT_RETRY_DELAY_MS);
    }

    ChatResilienceService(LlmProviderRegistry llmProviderRegistry, ToolCallbackProvider weatherToolProvider,
                          long retryDelayMs) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.weatherToolProvider = weatherToolProvider;
        this.retryDelayMs = retryDelayMs;
    }

    public Flux<String> streamChat(String conversationId, String message, List<Message> history) {
        var outputStarted = new AtomicBoolean(false);
        var inReasoning = new AtomicBoolean(false);
        var reasoningStarted = new AtomicBoolean(false);

        return Flux.defer(() -> {
                    ProviderConfig config = llmProviderRegistry.getDefaultProviderConfig();
                    OpenAIClient client = llmProviderRegistry.getDefaultOpenAiClient();

                    ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                            .model(config.getModel());

                    if (config.getTemperature() != null) {
                        paramsBuilder.temperature(config.getTemperature());
                    }

                    if (history != null && !history.isEmpty()) {
                        for (Message m : history) {
                            if (m instanceof UserMessage) {
                                paramsBuilder.addUserMessage(m.getText());
                            } else if (m instanceof AssistantMessage) {
                                paramsBuilder.addAssistantMessage(m.getText());
                            } else if (m instanceof SystemMessage) {
                                paramsBuilder.addSystemMessage(m.getText());
                            }
                        }
                    }

                    paramsBuilder.addUserMessage(message);
                    paramsBuilder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
                    ChatCompletionCreateParams params = paramsBuilder.build();

                    return Flux.<String>create(sink -> {
                        try {
                            AtomicBoolean usageCaptured = new AtomicBoolean(false);
                            StringBuilder totalContentSb = new StringBuilder();
                            StringBuilder totalReasoningSb = new StringBuilder();

                            client.chat().completions().createStreaming(params).stream().forEach(chunk -> {
                                // 🌟 核心防漏算：实时从 chunk.usage 捕获流式 Token（含推理思考 Token）
                                chunk.usage().ifPresent(usage -> {
                                    usageCaptured.set(true);
                                    long promptTokens = usage.promptTokens();
                                    long compTokens = usage.completionTokens();
                                    long totalTokens = usage.totalTokens();
                                    long actualGen = Math.max(compTokens, totalTokens - promptTokens);
                                    LlmCostMeterAdvisor.recordManualTokens(promptTokens, actualGen, config.getModel());
                                    TraceManager.tag("llm.model", config.getModel());
                                    TraceManager.tag("llm.promptTokens", promptTokens);
                                    TraceManager.tag("llm.compTokens", actualGen);
                                    TraceManager.tag("llm.totalTokens", totalTokens);
                                });

                                for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                                    var delta = choice.delta();
                                    String text = delta.content().orElse("");
                                    String reasoning = "";

                                    Map<String, JsonValue> additional = delta._additionalProperties();
                                    if (additional != null && additional.containsKey("reasoning_content")) {
                                        JsonValue node = additional.get("reasoning_content");
                                        if (node != null && node.asString().isPresent()) {
                                            reasoning = String.valueOf(node.asString().get());
                                        }
                                    }

                                    StringBuilder sb = new StringBuilder();

                                    if (!reasoning.isEmpty()) {
                                        totalReasoningSb.append(reasoning);
                                        if (!reasoningStarted.get()) {
                                            reasoningStarted.set(true);
                                            inReasoning.set(true);
                                            sb.append("> 💭 **思考过程：**\n> \n> ");
                                        }
                                        sb.append(reasoning.replace("\n", "\n> "));
                                    }

                                    if (!text.isEmpty()) {
                                        totalContentSb.append(text);
                                        if (inReasoning.get()) {
                                            inReasoning.set(false);
                                            sb.append("\n\n---\n\n");
                                        }
                                        sb.append(text);
                                    }

                                    String out = sb.toString();
                                    if (!out.isEmpty()) {
                                        sink.next(out);
                                    }
                                }
                            });

                            // 🌟 兜底：若厂商流式接口未返回 chunk.usage，根据传输字符量智能换算补录
                            if (!usageCaptured.get()) {
                                long promptChars = message.length() + (history != null ? history.stream().mapToInt(m -> m.getText() != null ? m.getText().length() : 0).sum() : 0);
                                long estPromptTokens = Math.max(10, (long)(promptChars * 1.3));
                                long estGenTokens = Math.max(5, (long)((totalContentSb.length() + totalReasoningSb.length()) * 1.3));
                                LlmCostMeterAdvisor.recordManualTokens(estPromptTokens, estGenTokens, config.getModel());
                                TraceManager.tag("llm.model", config.getModel());
                                TraceManager.tag("llm.tokensEstimated", estPromptTokens + estGenTokens);
                            }

                            sink.complete();
                        } catch (Exception e) {
                            sink.error(e);
                        }
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                })
                .doOnNext(text -> outputStarted.set(true))
                .retryWhen(Retry.backoff(MAX_ATTEMPTS - 1, Duration.ofMillis(retryDelayMs))
                        .filter(error -> !outputStarted.get())
                        .doBeforeRetry(signal -> log.warn("流式调用第 {} 次失败，准备重试：{}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .onErrorResume(e -> {
                    String errorMessage = outputStarted.get() ? STREAM_INTERRUPTED_RESPONSE : FALLBACK_RESPONSE;
                    log.warn("流式调用失败（已输出分片={}）：{}", outputStarted.get(), e.getMessage());
                    return Flux.just(errorMessage);
                });
    }
}