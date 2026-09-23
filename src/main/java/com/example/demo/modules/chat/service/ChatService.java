package com.example.demo.modules.chat.service;

import com.example.demo.modules.chat.model.entity.ChatMessage;
import com.example.demo.modules.chat.model.entity.ChatSession;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.ai.PromptSanitizer;
import com.example.demo.common.ai.PromptSecurityConstants;
import com.example.demo.common.ai.trace.TraceContext;
import com.example.demo.common.ai.trace.TraceManager;
import com.example.demo.modules.chat.mapper.ChatMessageMapper;
import com.example.demo.modules.knowledgebase.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话聊天的编排层：
 * 职责：会话归属校验 + 数据库落库(长期记忆) + Redis缓存(短期记忆) + 知识库关联与RAG增强 + 把 AI 交互交给 ChatResilienceService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final ChatResilienceService chatResilienceService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatMemoryService chatMemoryService;
    private final PromptSanitizer promptSanitizer;

    /**
     * 会话消息统一入口（流式）：Redis 驱动的短期对话记忆 + 自动绑定会话关联知识库的 RAG 检索。
     *
     * <ol>
     *   <li>校验会话归属；</li>
     *   <li>获取该会话关联的知识库列表；</li>
     *   <li>从 Redis (未命中则回源 DB) 加载短期上下文历史消息；</li>
     *   <li>用户原始问题写入数据库（长期记忆）并追加到 Redis；</li>
     *   <li>如果关联了知识库，进行带知识库过滤的 RAG 语义搜索并注入 Prompt；</li>
     *   <li>流式调用 AI：历史消息注入到 prompt 中，逐块返回正文；</li>
     *   <li>流结束后，把完整 AI 回复写入数据库、追加到 Redis 并刷新会话更新时间。</li>
     * </ol>
     */
    public Flux<String> streamChat(
            Long sessionId,
            Long userId,
            String content) {

        // 1. 开启当前对话的全局 Trace 根节点，并自动进行 MDC 日志染色
        TraceManager.startTrace("chat.rag.stream");
        TraceManager.tag("sessionId", sessionId);
        TraceManager.tag("userId", userId);

        // 1.1 检查 Session 是否属于当前用户
        ChatSession session = chatSessionService.findByIdAndUserId(sessionId, userId);
        if (session == null) {
            TraceManager.endTrace();
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }

        // 2. 获取该会话关联的所有知识库 ID
        List<Long> knowledgeBaseIds = chatSessionService.getKnowledgeBaseIds(sessionId);
        TraceManager.tag("kbIds", knowledgeBaseIds);

        // 3. 从 Redis/DB 加载滚动前情提要与短期历史上下文（最近 4 条）
        String summary = chatMemoryService.getSummary(sessionId);
        List<Message> history = TraceManager.record("chat.memory.load", () -> {
            List<Message> hist = chatMemoryService.getHistory(sessionId);
            TraceManager.tag("historySize", hist.size());
            TraceManager.tag("hasSummary", !summary.isBlank());
            return hist;
        });

        // 4. 用户原始问题落库（长期记忆）+ 写入 Redis（短期记忆）
        insert(sessionId, "user", content);
        chatMemoryService.append(sessionId, "user", content);

        // 4.1 如果会话还是默认的 "新对话"，基于首句提问自动异步提炼智能主题标题（ChatGPT 同款体验）
        if (session.getTitle() == null || "新对话".equals(session.getTitle().trim())) {
            chatSessionService.asyncInferSmartTitle(sessionId, content);
        }

        // 5. RAG 搜索与前情摘要注入：结合知识库文档切片与滚动摘要注入 Prompt
        String enhancedContent = content;
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            enhancedContent = TraceManager.record("rag.knowledge.enhance", () ->
                    enhanceWithRAG(content, knowledgeBaseIds, summary)
            );
        } else if (!summary.isBlank()) {
            enhancedContent = String.format("【前情提要】：%s\n\n用户问题：%s", summary, content);
        }

        // 6. 流式调用 AI：一边推给前端，一边累计正文；流结束后把完整回复落库与写入 Redis
        final long streamStart = System.currentTimeMillis();
        var firstChunkLogged = new java.util.concurrent.atomic.AtomicBoolean(false);
        StringBuilder assistantText = new StringBuilder();

        TraceContext capturedTraceContext = TraceManager.getContext();

        return chatResilienceService.streamChat(String.valueOf(sessionId), enhancedContent, history)
                .doOnNext(text -> {
                    if (!firstChunkLogged.getAndSet(true)) {
                        long ttft = System.currentTimeMillis() - streamStart;
                        if (capturedTraceContext != null && capturedTraceContext.getRootSpan() != null) {
                            capturedTraceContext.getRootSpan().tag("ttftMs", ttft);
                        }
                    }
                    accumulateContent(text, assistantText);
                })
                .doOnComplete(() -> {
                    String fullReply = assistantText.toString();
                    insert(sessionId, "assistant", fullReply);
                    chatMemoryService.append(sessionId, "assistant", fullReply);
                    chatSessionService.updateUpdatedAt(sessionId);

                    // 异步触发增量滚动摘要计算（ConversationSummaryBufferMemory 引擎）
                    chatMemoryService.asyncCheckAndCompressSummary(sessionId, content, fullReply);

                    // 🌟 结束 Trace 并打印直观优美的 ASCII 链路调用树报告
                    TraceContext completed = TraceManager.endTrace();
                    if (completed != null) {
                        log.info("{}", TraceManager.renderAsciiTree(completed));
                    }
                })
                .doOnError(err -> {
                    TraceContext completed = TraceManager.endTrace();
                    if (completed != null) {
                        log.error("❌ [Trace] 流式对话异常中断\n{}", TraceManager.renderAsciiTree(completed));
                    }
                });
    }

    /**
     * RAG 增强：基于关联知识库搜索相关文档，并融合前情提要（保持客观纯粹）
     */
    private String enhanceWithRAG(String question, List<Long> knowledgeBaseIds, String summary) {
        try {
            // 动态自适应搜索：短句宽召回、中长句精召回（Top-K 自动 2~4 档位）+ 相似度阈值过滤 + 确定性排序
            List<Document> docs = TraceManager.record("rag.vector.search", () -> {
                List<Document> result = knowledgeBaseService.search(question, knowledgeBaseIds);
                TraceManager.tag("candidateHitCount", result.size());
                return result;
            });

            String summaryPrefix = (summary != null && !summary.isBlank()) ? "【前情提要】：\n" + summary + "\n\n" : "";

            if (docs.isEmpty()) {
                TraceManager.tag("emptyRecall", true);
                return String.format("""
                        %s用户在选定的知识库中提出了以下问题，但在知识库中未能检索到直接相关的信息。
                        请明确告知用户知识库中未提及该内容，并基于通用常识给出简要解答与建议。
                        
                        用户问题：%s
                        """, summaryPrefix, question);
            }

            // 拼装检索命中的切片上下文 (确定性排布，利于大模型服务端命中 Prompt Caching)
            String context = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            // 🛡️ 安全防御加固：不可预测的随机 UUID 动态边界包裹 + 恶意模式净化
            String safeContext = TraceManager.record("rag.prompt.sanitize", () -> {
                TraceManager.tag("antiInjection", true);
                TraceManager.tag("boundaryStrategy", "dynamic-uuid-8");
                return promptSanitizer != null
                        ? promptSanitizer.wrapWithBoundary("knowledge-context", context)
                        : context;
            });

            String safeQuestion = promptSanitizer != null
                    ? promptSanitizer.wrapWithBoundary("user-question", question)
                    : question;

            TraceManager.tag("contextLength", context.length());
            TraceManager.tag("docsCount", docs.size());

            // 注入到 Prompt 中（前情摘要与静态背景在前，安全铁律守门，动态问题在后）
            return String.format("""
                    %s请根据以下知识库内容回答问题。如果知识库中没有相关信息，请明确说明。回答要准确、专业、清晰。
                    %s
                    
                    知识库内容：
                    %s
                    
                    用户问题：
                    %s
                    
                    请基于知识库内容回答：
                    """, summaryPrefix, PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION, safeContext, safeQuestion);

        } catch (Exception e) {
            // RAG 搜索失败，降级为原问题
            log.warn("[RAG] 搜索异常，降级使用原始问题: {}", e.getMessage(), e);
            TraceManager.tag("ragFallback", true);
            return question;
        }
    }

    /** 查询会话消息，带归属校验：不是自己的会话返回 403。 */
    public List<ChatMessage> findBySessionId(Long sessionId, Long userId) {
        ChatSession session = chatSessionService.findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该聊天会话");
        }

        return chatMessageMapper.findBySessionId(sessionId);
    }

    private ChatMessage insert(Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        chatMessageMapper.insert(message);
        return message;
    }

    /** 直接累计纯文本分片，用于流结束后拼出完整回复落库。 */
    private void accumulateContent(String text, StringBuilder assistantText) {
        assistantText.append(text);
    }
}
