# 06 · Chat 编排与记忆

覆盖：`ChatService`（会话聊天的编排层）和**双层记忆系统**（短期内存 + 长期数据库如何打通）。

## ChatService —— 编排层，不碰 AI 细节

`ChatService` 是会话聊天的"导演"：它不关心重试、SSE 信封这些 AI 交互细节（那是 `ChatResilienceService` 的活），只做**编排**——归属校验、落库、攒正文。这也是当初从 `ChatMessageService` 改名来的原因：名字要配得上职责。

```java
public Flux<String> streamChat(Long sessionId, Long userId, String content) {
    // ① 归属校验：不是你的会话 → 403
    ChatSession session = chatSessionService.findByIdAndUserId(sessionId, userId);
    if (session == null) throw new ResponseStatusException(FORBIDDEN, "无权访问该聊天会话");

    String memoryKey = String.valueOf(sessionId);   // 短期记忆 key 直接复用 sessionId

    // ② 短期记忆预热：窗口空（如重启后）用 DB 历史灌进去
    if (chatResilienceService.hasNoMemory(memoryKey)) {
        chatResilienceService.seedMemory(memoryKey, getSpringAiMessages(sessionId));
    }

    // ③ 用户消息落库（长期记忆）
    insert(sessionId, "user", content);

    // ④ 流式调用 AI + 边攒正文；流结束把完整回复落库
    StringBuilder assistantText = new StringBuilder();
    return chatResilienceService.streamChat(memoryKey, content)
            .doOnNext(envelope -> accumulateContent(envelope, assistantText))
            .doOnComplete(() -> insert(sessionId, "assistant", assistantText.toString()));
}
```

**5 步的每一步都在为什么服务：**

| 步   | 代码       | 目的                     |
| --- | -------- | ---------------------- |
| ①   | 归属校验     | 安全：拿别人的会话 id 发消息 → 403 |
| ②   | 记忆预热     | 重启后短期记忆空了，从 DB 找回上下文   |
| ③   | 用户消息落库   | 用户的发言先进长期记忆            |
| ④   | 流式 + 攒正文 | 一边推前端，一边拼完整回答          |
| ⑤   | 回复落库     | 流结束后，完整回复进长期记忆         |

### accumulateContent —— 只攒正文

```java
private void accumulateContent(String envelope, StringBuilder assistantText) {
    JsonNode node = objectMapper.readTree(envelope);
    if ("content".equals(node.path("type").asText())) {
        assistantText.append(node.path("text").asText());
    }
}
```

信封有三种：`reasoning` / `content` / `error`。落库只需要**正文**（思考过程和错误提示不该存进历史），所以只累加 `type=content`。**为什么要攒而不是直接拿**：模型是逐块吐的，只有流结束后才能拼出完整回答。错误分片解析失败直接忽略（`catch (JacksonException)`）。

### getSpringAiMessages —— DB 记录转回 Spring AI 消息

```java
private List<Message> getSpringAiMessages(Long sessionId) {
    return chatMessageMapper.findBySessionId(sessionId).stream()
            .map(m -> "user".equals(m.getRole())
                    ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()))
            .toList();
}
```

预热的输入必须是 Spring AI 认得的 `Message` 类型（`UserMessage` / `AssistantMessage`），所以把数据库记录翻译回去。

## 双层记忆系统 —— 短期 + 长期怎么打通

这是整个项目最核心的架构概念，用一张图讲清：

```
                   短期记忆（进程内存）            长期记忆（数据库）
                 ChatResilienceService          ChatService（经 Mapper）
                ┌─────────────────────┐        ┌─────────────────────┐
  每轮对话自动读写│ Map<sessionId,        │        │ chat_messages 表       │
     (Advisor)   │   MessageWindow>      │        │   role + content      │
                └──────────┬──────────┘        └──────────┬──────────┘
                           │ 重启后清空                    │ 永久保存
                           └─────────seedMemory───────────┘
                              （用 DB 历史灌回窗口）
```

| 维度 | 短期记忆（ChatMemory） | 长期记忆（DB） |
|---|---|---|
| 存哪 | 进程内存 `Map` | PostgreSQL `chat_messages` |
| 生命周期 | 重启就丢 | 永久 |
| 容量 | 滑动窗口 10 条 ≈ 5 轮 | 无限 |
| 谁读写 | `PromptChatMemoryAdvisor` 自动 | `ChatService` 手动 |
| 作用 | 给模型的**上下文窗口** | 历史存档 + 重启恢复 |

### 三个连接点

1. **平时聊天**：短期记忆由 Advisor 每轮自动读写，业务代码不碰。DB 里 `ChatService` 手动落用户消息 + 流结束落回复。
2. **重启后**：短期记忆是空的 → `ChatService.streamChat` 里 `hasNoMemory` 判断 → `seedMemory` 把 DB 最近历史灌进窗口 → 用户感觉不到断档。
3. **删除会话**：`ChatSessionService.delete`（`@Transactional`）删 DB 两张表 + `deleteConversation` 清内存窗口，三处同步。

### 为什么短期记忆是"滑动窗口"而不是全部

模型上下文长度有限，塞不下全部历史。`MessageWindowChatMemory(maxMessages=10)` 自动淘汰最老的，永远保留最近 5 轮。代价：5 轮之前的"记忆"只存在于 DB，模型看不到——这是容量 vs 记忆的经典取舍。

### 记忆的 key 设计

短期记忆的 key 直接是 `String.valueOf(sessionId)`。因为走的是会话路径，天然按会话隔离，不需要额外的 UUID。DB 里靠 `session_id` 外键关联，也是同一套 id——两边对得上，才能互相预热。

> 相关：[[04-Service层-用户与会话]] · [[05-Chat核心-流式与重试]] · [[07-数据访问层]] · [[10-前端]]
