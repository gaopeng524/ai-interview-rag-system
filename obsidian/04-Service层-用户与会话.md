# 04 · Service 层：用户与会话

覆盖：`UserService`、`ChatSessionService`。这是"厚 Service"原则的核心——业务逻辑、归属校验、事务全在这层。

## UserService —— 注册 / 登录 / 查用户

### 注册：BCrypt 加密存储

```java
public UserResponse createUser(User user) {
    // 1. 密码加密（明文绝不允许入库）
    String encodedPassword = passwordEncoder.encode(user.getPassword());
    user.setPassword(encodedPassword);
    // 2. 保存数据库
    userMapper.insert(user);
    // 3. Entity → DTO（把密码挡在门外）
    return toResponse(user);
}
```

**BCrypt 是什么**：不是普通加密，是**加盐哈希**。普通 MD5 同一密码结果相同（撞库风险），BCrypt 每次用随机盐，`123456` 存出来每次都不同，而且计算故意很慢（拖慢暴力破解）。`BCryptPasswordEncoder` 是 `passwordEncoder` Bean（见 [[02-安全与JWT]] 的 SecurityConfig）。

### 登录：一条刻意为之的提示语

```java
public String login(LoginRequest request) {
    User user = userMapper.findByUsername(request.getUsername());
    // 用户名不存在和密码错误用同一句话
    boolean matches = user != null
            && passwordEncoder.matches(request.getPassword(), user.getPassword());

    if (!matches) {
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
    return jwtService.generateToken(user.getUsername(), user.getId());
}
```

`matches(明文, 库里的哈希)` 会自己完成：解析哈希 → 取出盐 → 重新算一遍 → 比对。返回 true 才放行。

**为什么"用户名不存在"和"密码错误"用同一句话**：如果接口返回"用户名不存在"，等于告诉攻击者"这个账号没注册过"，方便撞库。统一文案是不泄露信息的标准做法。

### 脱敏 DTO

```java
private UserResponse toResponse(User user) {
    UserResponse response = new UserResponse();
    response.setId(user.getId());
    response.setUsername(user.getUsername());
    return response;   // password 不赋值，天然不泄露
}
```

> ⚠️ 真实事故：数据库里 `alice`、`test` 两个用户的密码是**明文**存的（历史 SQL 插入的），登录一直失败——因为 `matches` 拿明文去当哈希解析，必然 false。教训：**永远走 `createUser` 注册，别手插 SQL**。详见 [[12-坑与排查]]。

## ChatSessionService —— 会话生命周期

```java
@Service
public class ChatSessionService {

    public ChatSession create(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title);
        chatSessionMapper.insert(session);
        return session;   // useGeneratedKeys 回填了 id
    }

    public List<ChatSession> findByUserId(Long userId) {
        return chatSessionMapper.findByUserId(userId);  // 按 created_at 倒序
    }

    public ChatSession findByIdAndUserId(Long id, Long userId) {
        return chatSessionMapper.findByIdAndUserId(id, userId);  // 归属校验的底层
    }
```

### 删除：一个事务里做三件事

```java
@Transactional
public void delete(Long id, Long userId) {
    ChatSession session = findByIdAndUserId(id, userId);
    if (session == null) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该聊天会话");
    }
    chatMessageMapper.deleteBySessionId(id);      // ① 先删消息（外键依赖）
    chatSessionMapper.deleteById(id);             // ② 再删会话
    chatResilienceService.deleteConversation(String.valueOf(id));  // ③ 清短期记忆
}
```

三个设计点：

1. **`@Transactional`**：三件事要么全成功、要么全回滚。删了一半崩了会留下孤儿数据。
2. **先删消息再删会话**：`chat_messages` 有外键指向 `chat_sessions`，不先删子表，删父表会被外键挡住。
3. **连带清短期记忆**：数据库和内存窗口是两套存储（[[06-Chat编排与记忆]]），删除时必须一起清，否则内存里残留一个"幽灵会话"。

### 归属校验（关键安全点）

`findByIdAndUserId` 的 SQL 是 `WHERE id = ? AND user_id = ?`——**一次查询就把"存在"和"属于你"两个条件都判了**。返回 null 就说明要么不存在、要么不是你的，统一抛 403。这比"先查存在，再查归属"更简洁，也避免暴露"这个会话存不存在"。

> 相关：[[03-Controller层]] · [[05-Chat核心-流式与重试]] · [[06-Chat编排与记忆]] · [[07-数据访问层]] · [[08-异常处理]]
