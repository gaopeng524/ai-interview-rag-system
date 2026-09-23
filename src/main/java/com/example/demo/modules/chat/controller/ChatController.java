package com.example.demo.modules.chat.controller;

import com.example.demo.common.annotation.RateLimit;
import com.example.demo.modules.chat.model.dto.RagChatDTO;
import com.example.demo.modules.chat.model.entity.ChatMessage;
import com.example.demo.common.result.Result;
import com.example.demo.common.security.UserPrincipal;
import com.example.demo.modules.chat.service.ChatService;
import com.example.demo.modules.chat.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 会话资源树 {@code /api/rag-chat/sessions} 的唯一入口（薄 Controller 设计）：
 * 控制器层仅负责参数接收、路由映射、调用 Service 层和封装 Result 统一返回。
 */
@RestController
@RequestMapping("/api/rag-chat/sessions")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatService chatService;

    // ---------- 会话生命周期 ----------

    /**
     * 创建新会话（带关联知识库列表与可选标题）
     * POST /api/rag-chat/sessions
     */
    @PostMapping
    public Result<RagChatDTO.SessionResponse> create(
            @RequestBody(required = false) RagChatDTO.CreateSessionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(chatSessionService.create(principal.id(), request));
    }

    /**
     * 获取当前用户的会话列表（侧边栏展示用，包含知识库名称、消息数、置顶状态等）
     * GET /api/rag-chat/sessions
     */
    @GetMapping
    public Result<List<RagChatDTO.SessionListItem>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(chatSessionService.listSessions(principal.id()));
    }

    /**
     * 获取会话详情（包含消息历史与关联知识库）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public Result<RagChatDTO.SessionDetail> getDetail(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(chatSessionService.getSessionDetail(sessionId, principal.id()));
    }

    /**
     * 更新会话标题
     * PUT /api/rag-chat/sessions/{sessionId}/title
     */
    @PutMapping("/{sessionId}/title")
    public Result<Void> updateTitle(
            @PathVariable Long sessionId,
            @RequestBody RagChatDTO.UpdateTitleRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        chatSessionService.updateTitle(sessionId, principal.id(), request);
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/{sessionId}/pin")
    public Result<Void> togglePin(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        chatSessionService.togglePin(sessionId, principal.id());
        return Result.success(null);
    }

    /**
     * 更新会话关联的知识库
     * PUT /api/rag-chat/sessions/{sessionId}/knowledge-bases
     */
    @PutMapping("/{sessionId}/knowledge-bases")
    public Result<Void> updateKnowledgeBases(
            @PathVariable Long sessionId,
            @RequestBody RagChatDTO.UpdateKnowledgeBasesRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        chatSessionService.updateKnowledgeBases(sessionId, principal.id(), request);
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        chatSessionService.delete(sessionId, principal.id());
        return Result.success(null);
    }

    // ---------- 会话内的流式消息 ----------

    /**
     * 发送消息（流式 SSE，自动结合该会话关联的知识库进行 RAG 检索）
     * POST /api/rag-chat/sessions/{sessionId}/messages/stream
     */
    @PostMapping(value = "/{sessionId}/messages/stream", produces = "text/event-stream;charset=UTF-8")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 15, interval = 60, message = "提问过于频繁，请稍后再试")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60, interval = 60, message = "当前系统并发较高，请稍后再试")
    public Flux<String> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody RagChatDTO.SendMessageRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return chatService.streamChat(sessionId, principal.id(), request.getMessageContent());
    }

    /**
     * 查历史消息
     * GET /api/rag-chat/sessions/{sessionId}/messages
     */
    @GetMapping("/{sessionId}/messages")
    public Result<List<ChatMessage>> getMessages(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(chatService.findBySessionId(sessionId, principal.id()));
    }
}
