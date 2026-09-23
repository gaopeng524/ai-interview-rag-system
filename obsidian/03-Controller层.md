# 03 · Controller 层

覆盖：`ChatController`、`UserController`。核心原则——**Controller 必须薄**，只负责"收参数、转发给 Service"，不写业务。

## 为什么 Controller 要薄

Controller 是 HTTP 的翻译官：

```
HTTP 请求（路径/参数/JSON）  →  Controller 拆出来  →  Service 处理  →  结果  →  HTTP 响应
```

如果把归属校验、记忆预热、落库逻辑写进 Controller，会得到：一个几百行的类、无法单元测试（测 Controller 要起整个 Web 容器）、业务逻辑和 HTTP 细节纠缠。所以全项目的 Controller 每个方法都只有 1~3 行，全部转发。

## ChatController —— /chat/sessions 资源树

```java
@RestController
@RequestMapping("/chat/sessions")
public class ChatController {

    private final ChatSessionService chatSessionService;   // 会话生命周期
    private final ChatService chatService;                 // 会话内聊天

    public ChatController(ChatSessionService s, ChatService c) {
        this.chatSessionService = s;
        this.chatService = c;
    }
```

5 个端点，全转发：

| 方法     | 路径                             | 转发到                               | 说明            |
| ------ | ------------------------------ | --------------------------------- | ------------- |
| GET    | `/chat/sessions`               | `chatSessionService.findByUserId` | 当前用户会话列表，侧边栏用 |
| POST   | `/chat/sessions?title=xxx`     | `chatSessionService.create`       | 新建会话          |
| DELETE | `/chat/sessions/{id}`          | `chatSessionService.delete`       | 删除会话（含归属校验）   |
| GET    | `/chat/sessions/{id}/messages` | `chatService.findBySessionId`     | 历史消息          |
| POST   | `/chat/sessions/{id}/messages` | `chatService.streamChat`          | 发消息（SSE 流式）   |

两个值得注意的设计：

### 1. 流式端点标注
```java
@PostMapping(value = "/{sessionId}/messages", produces = "text/event-stream;charset=UTF-8")
public Flux<String> sendMessage(...) {
    return chatService.streamChat(sessionId, principal.id(), request.getContent());
}
```
- `produces = "text/event-stream"`：告诉浏览器这是 SSE 流，不能一次拿到完整响应。
- 返回 `Flux<String>`（Reactor 响应式流）：不是返回一个结果，而是返回一个"结果流"，Spring MVC 会自动把它转成 SSE 一块块推给前端。

### 2. 当前用户从注解拿
```java
@AuthenticationPrincipal UserPrincipal principal
```
`principal.id()` 直接是当前登录用户的 id，不需要解析 token、不需要查库。这是 [[02-安全与JWT]] 里过滤器种下的种子，这里收获。

## UserController —— /users

```java
@RestController
@RequestMapping("/users")
public class UserController {
    @GetMapping          List<UserResponse> findAll()       // 用户列表（脱敏）
    @GetMapping("/{id}") UserResponse findById(Long id)     // 单个用户
    @PostMapping         UserResponse createUser(User user) // 注册
    @PostMapping("/login") String login(LoginRequest request) // 登录→返回 JWT 字符串
}
```

注意返回类型：
- 列表/详情返回 **`UserResponse`**（只有 id + username），**绝不含密码哈希**。
- 登录返回 **`String`**——就是 JWT token 本身，前端直接存 localStorage。
- `createUser` 接收的是 `User` 实体（有 password 字段），因为注册时要拿明文密码去加密。返回时又转回 `UserResponse`，密码不出门。

## 两个 Controller 的对比

| | ChatController | UserController |
|---|---|---|
| 资源 | 会话 + 消息 | 用户 |
| 认证 | 全部需要 JWT | 注册/登录公开，其余需 JWT |
| 返回 | 会话实体、消息列表、SSE 流、void | 脱敏 DTO、JWT 字符串 |

> 相关：[[04-Service层-用户与会话]] · [[05-Chat核心-流式与重试]] · [[06-Chat编排与记忆]] · [[10-前端]]
