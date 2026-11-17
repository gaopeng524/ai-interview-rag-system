# AI 学习项目完整总结

## 项目背景

### demo 项目（学习项目）
- **技术栈**：Spring Boot 4.1.0 + Spring AI + DeepSeek + MyBatis + PostgreSQL + JWT
- **功能**：注册/登录 → 会话管理 → 流式对话 → 短期/长期记忆
- **目标**：学习 Spring AI 和 RAG 技术

### interview-guide-ref 项目（参考项目）
- **技术栈**：Spring Boot 4.1.0 + Java 25 + Spring AI 2.0.0 + React 18
- **功能**：知识库管理、RAG 问答、面试模拟、语音面试等
- **作用**：作为参考，学习完整项目的架构和实现

---

## Day 1（2026-08-22）- AI 网关重构

### 1. Spring AI 版本升级：M4 → GA

**改动：**
```xml
<!-- 之前 -->
<spring-ai.version>2.0.0-M4</spring-ai.version>

<!-- 现在 -->
<spring-ai.version>2.0.0</spring-ai.version>
```

**原因：**
- GA 版本 API 更稳定
- 与参考项目 interview-guide-ref 一致
- 更好的文档和社区支持

### 2. LlmProviderRegistry 重构：统一 OpenAI 兼容协议

**改动前（M4 版本 - 分叉构建）：**
```java
// DeepSeek 走官方 SDK，其他走 OpenAI 兼容
private ChatClient createChatClient(String providerId) {
    ChatModel chatModel = isDeepSeek(providerId)
            ? buildDeepSeekChatModel(config)      // DeepSeek 官方
            : buildOpenAiCompatibleChatModel(config);  // OpenAI 兼容
    return ChatClient.builder(chatModel).build();
}
```

**改动后（GA 版本 - 统一构建）：**
```java
// 所有供应商统一走 OpenAI 兼容协议
private ChatModel buildChatModel(String providerId) {
    OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(baseUrl, apiKey);
    return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .build();
}
```

**为什么能统一：**
- DeepSeek API 兼容 OpenAI 协议（`/v1/chat/completions`）
- 不需要官方 SDK，用 OpenAI 兼容模式即可
- 代码更简洁，扩展性更强

### 3. ApiPathResolver 重构

**改动前（M4）：**
```java
import org.springframework.ai.openai.api.OpenAiApi;
// M4 版本用 OpenAiApi
```

**改动后（GA）：**
```java
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

// GA 版本用 OpenAI 官方 Java SDK
public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
    Timeout timeout = Timeout.builder()
            .connect(Duration.ofMillis(10000))
            .read(Duration.ofMillis(300000))
            .build();
    ClientOptions options = ClientOptions.builder()
            .apiKey(apiKey)
            .credential(BearerTokenCredential.create(apiKey))
            .baseUrl(resolveVersionedBaseUrl(baseUrl))
            .timeout(timeout)
            .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())  // 必须设置
            .build();
    return new OpenAIClientImpl(options);
}
```

### 4. ChatResilienceService：Advisor 类名变化

```java
// M4 版本
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
PromptChatMemoryAdvisor.builder(memory).build()

// GA 版本
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
MessageChatMemoryAdvisor.builder(memory).build()
```

### 5. 配置文件合并

**之前：** `application.yml` + `application.properties` 两份配置
**现在：** 只保留 `application.yml`，所有配置统一管理

### 6. pom.xml 改动

- 移除 `spring-ai-starter-model-deepseek` 依赖（统一用 OpenAI 兼容）
- 移除重复的 `spring-ai-starter-model-openai` 依赖
- 简化 maven-surefire-plugin 配置（移除 `${org.mockito:mockito-core:jar}`）

### 7. DemoApplication 改动

```java
@SpringBootApplication(exclude = {
    OpenAiChatAutoConfiguration.class,
    // ... 其他 OpenAI 相关
    // 移除了 DeepSeekChatAutoConfiguration（依赖已删除）
    PgVectorStoreAutoConfiguration.class  // 新增：暂时不用 RAG
})
```

### 8. GlobalExceptionHandler 改动

新增 `ClientAbortException` 处理器，避免客户端断开时二次报错：
```java
@ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
public void handleClientAbort(org.apache.catalina.connector.ClientAbortException e) {
    log.info("客户端中断连接：{}", e.getMessage());
}
```

### 9. 测试文件改动

`ChatResilienceServiceTest` 中 `DeepSeekAssistantMessage` 改为 `AssistantMessage`。

---

## Day 2（2026-08-23）- RAG 知识库功能

### 1. 知识库表结构

```sql
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    original_filename VARCHAR(500),
    file_size BIGINT,
    content_type VARCHAR(100),
    file_hash VARCHAR(64),
    category VARCHAR(100),
    vector_status VARCHAR(20) DEFAULT 'PENDING',
    vector_error VARCHAR(500),
    chunk_count INTEGER DEFAULT 0,
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 2. 新增文件（7 个）

| 文件 | 作用 |
|------|------|
| `KnowledgeBase.java` | 知识库实体 |
| `KnowledgeBaseMapper.java` | Mapper 接口 |
| `KnowledgeBaseMapper.xml` | SQL 映射 |
| `KnowledgeBaseService.java` | 核心服务（向量化 + 语义搜索 + RAG 问答） |
| `KnowledgeBaseController.java` | REST API |
| `FileParseService.java` | 文件解析服务（Tika） |
| `DatabaseInitConfig.java` | 启动时自动建表 |

### 3. API 接口

| 接口 | 方法 | 作用 |
|------|------|------|
| `/api/knowledgebase/list` | GET | 获取知识库列表 |
| `/api/knowledgebase/{id}` | GET | 获取知识库详情 |
| `/api/knowledgebase` | POST | 添加知识（手动输入） |
| `/api/knowledgebase/upload` | POST | 上传文件 |
| `/api/knowledgebase/{id}` | DELETE | 删除知识库 |
| `/api/knowledgebase/search?keyword=xxx` | GET | 搜索知识库 |
| `/api/knowledgebase/categories` | GET | 获取所有分类 |
| `/api/knowledgebase/stats` | GET | 获取统计信息 |
| `/api/knowledgebase/query` | POST | RAG 问答 |
| `/api/knowledgebase/query/stream` | POST | 流式 RAG 问答 |

### 4. 对齐 inter 前端 API 路径

**聊天接口路径变更：**
- `/chat/sessions` → `/api/rag-chat/sessions`
- `/{id}/messages` → `/{id}/messages/stream`

**新增接口：**
- `GET /api/rag-chat/sessions/{id}` - 获取会话详情（包含消息历史）

**请求格式兼容：**
- `ChatMessageRequest` 同时支持 `question` 和 `content` 字段

### 5. SSE 输出格式变更

**之前：** JSON 格式 `{"type":"content","text":"..."}`
**现在：** 纯文本格式 `...`

**原因：** 对齐 inter 前端的 SSE 解析方式

### 6. Embedding 模型配置

**配置方式：**
```yaml
app:
  ai:
    providers:
      dashscope:
        base-url: https://dashscope.aliyuncs.com/compatible-mode
        api-key: ${DASHSCOPE_API_KEY}
        model: qwen-turbo
        embedding-model: text-embedding-v3
```

**使用方式：**
- 聊天用 DeepSeek（便宜、推理强）
- 向量化用 DashScope（embedding 效果好）

### 7. pgvector 向量存储

**依赖：**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

**配置：**
```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1024
        initialize-schema: true
```

### 8. inter 前端改动（5 个文件）

| 文件 | 改动 |
|------|------|
| `Layout.tsx` | 添加登录按钮和用户信息显示 |
| `LoginDialog.tsx` | 新增登录对话框组件 |
| `request.ts` | 添加 JWT token 请求拦截器 + 401 处理 |
| `stream.ts` | 添加 JWT token 到 SSE 请求 |
| `vite.config.ts` | 配置 API 代理到 demo 后端 |

**如何恢复原始版本：**
```powershell
cd D:\Javacode\interview-guide-ref
git checkout frontend/
```

---

## 关键踩坑记录

### 1. URL 重复拼接 `/v1/v1`

**问题：**
```
POST https://api.deepseek.com/v1/v1/chat/completions
                    ^^^ ^^^
                 两个 /v1 重复了！
```

**原因：**
- M4 版本 `OpenAiApi` 内部会拼 `/v1`
- `ApiPathResolver` 又补了一次 `/v1`

**解决方案：**
- GA 版本用 `OpenAIClient`，`ApiPathResolver` 补 `/v1`
- `OpenAIClient` 不会自动拼 `/v1`

### 2. OpenAiEmbeddingModel 构造函数

**问题：**
```
java: 找不到符号
  符号:   方法 builder()
  位置: 类 org.springframework.ai.openai.OpenAiEmbeddingModel
```

**原因：**
- M4 版本没有 `builder()` 方法
- 需要用构造函数 `new OpenAiEmbeddingModel(api, MetadataMode.EMBED)`

**解决方案：**
```java
// M4 版本
return new OpenAiEmbeddingModel(api, MetadataMode.EMBED);

// GA 版本
return OpenAiEmbeddingModel.builder()
    .openAiClient(client)
    .options(options)
    .build();
```

### 3. httpClient 必须设置

**问题：**
```
java.lang.IllegalStateException: `httpClient` is required, but was not set
```

**原因：**
- GA 版本的 `ClientOptions` 必须设置 `httpClient`

**解决方案：**
```java
ClientOptions options = ClientOptions.builder()
    .apiKey(apiKey)
    .credential(BearerTokenCredential.create(apiKey))
    .baseUrl(resolveVersionedBaseUrl(baseUrl))
    .timeout(timeout)
    .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())  // 必须设置
    .build();
```

### 4. PgVectorStore 自动配置

**问题：**
```
Parameter 0 of method vectorStore ... required a bean of type 'EmbeddingModel' that could not be found.
```

**原因：**
- 添加 `spring-ai-starter-vector-store-pgvector` 依赖后，需要 `EmbeddingModel` bean
- 或者排除自动配置

**解决方案：**
```java
@SpringBootApplication(exclude = {
    // ... 其他排除
    PgVectorStoreAutoConfiguration.class  // 排除 pgvector 自动配置
})
```

### 5. SSE 输出格式

**问题：**
- inter 前端期望纯文本
- demo 后端返回 JSON 格式 `{"type":"content","text":"..."}`

**解决方案：**
```java
// 之前：返回 JSON 格式
.map(msg -> toEnvelope("content", msg.getText()))

// 现在：直接返回纯文本
.map(msg -> msg.getText())
```

### 6. accumulateContent 方法

**问题：**
- 改成纯文本后，`accumulateContent` 还在解析 JSON
- 导致 AI 回复无法保存到数据库

**解决方案：**
```java
// 之前：解析 JSON
private void accumulateContent(String envelope, StringBuilder assistantText) {
    JsonNode node = objectMapper.readTree(envelope);
    if ("content".equals(node.path("type").asText())) {
        assistantText.append(node.path("text").asText());
    }
}

// 现在：直接追加纯文本
private void accumulateContent(String text, StringBuilder assistantText) {
    assistantText.append(text);
}
```

### 7. createdAt 为空

**问题：**
- 数据库字段没有默认值
- 前端 `new Date("")` 返回 Invalid Date

**解决方案：**
```java
// 之前
msg.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");

// 现在
String now = java.time.LocalDateTime.now().toString();
msg.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : now);
```

---

## 当前状态

### 已完成功能

1. ✅ Spring AI 2.0.0 GA 升级
2. ✅ AI 网关统一（OpenAI 兼容协议）
3. ✅ RAG 知识库功能（添加知识、语义搜索、RAG 问答）
4. ✅ 文件上传（PDF/Word/TXT 解析）
5. ✅ API 路径与数据结构完全对齐 inter 前端（支持关联多知识库）
6. ✅ 会话与知识库多对多绑定（`chat_session_knowledge_bases`）、置顶、标题重命名
7. ✅ RAG 向量过滤搜索（`kb_id in ['...']` + 本地兜底过滤），提问自动带上勾选的知识库
8. ✅ 登录功能（JWT）与统一 `Result` 报文响应
9. ✅ pgvector 向量存储

### 已解决的关键问题

1. ✅ **勾选的知识库在 RAG 中未生效 / 会话详情不显示勾选知识库**：
   - **原因**：前端创建会话时传入 `knowledgeBaseIds`，但后端原接口仅接收 `title` 并丢弃了 `knowledgeBaseIds`；发消息流接口只传 `question`，导致后端 RAG 拿到 `knowledgeBaseId == null` 且未根据会话查询关联知识库；会话详情接口也硬编码返回空知识库列表。
   - **解决**：新增 `chat_session_knowledge_bases` 关联表；会话创建、详情、更新知识库全链路维护 `knowledgeBaseIds`；`ChatService` 自动根据 `sessionId` 读取关联知识库并执行带 `kb_id` 过滤的向量相似度搜索注入 Prompt。

2. ✅ **inter 前端切换会话后消息和知识库勾选不显示**：
   - **原因**：后端 `GET /api/rag-chat/sessions/{sessionId}` 返回空知识库列表，前端 `setSelectedKbIds` 被置空；另外接口未包装统一 `Result`。
   - **解决**：对齐 `RagChatSessionDetail` 报文，返回真实关联的 `knowledgeBases` 与历史 `messages`，并由统一 `Result.success` 包裹。

3. ✅ **知识库上传短文本切片为 0（`chunk_count = 0`）**：
   - **原因**：Spring AI 的 `TokenTextSplitter` 默认按长文本窗口切分，文本过短（几个字）时直接返回空列表 `[]`，导致向量库未生成切片。
   - **解决**：在 `KnowledgeBaseService.vectorizeAndStore` 中增加短文本保底机制：若切片器产出为空，自动将整篇短文本作为一个独立 Chunk 存入向量库。

4. ✅ **Windows 记事本 ANSI/GBK 编码乱码**：
   - **原因**：Windows 记事本默认编码可能不是 UTF-8，直接解析产生 `\uFFFD` 乱码字符。
   - **解决**：在 `FileParseService` 中增加编码探测与 GBK 自动回退支持。

5. ✅ **前端请求 `GET /api/knowledgebase/{id}/questions` 报 404**：
   - **原因**：前端在知识库详情页自动请求关联面试题库列表，后端未提供路由。
   - **解决**：在 `KnowledgeBaseController` 中增加兜底接口返回空列表 `Result.success(List.of())`，消除 404 Warning。

6. ✅ **DTO 类爆炸重构为 Java Record 聚合类**：
   - **解决**：参考 `interview-guide-ref` 规范，将原本平铺的 16 个独立 DTO 重构为 3 个领域聚合 Record 文件（`RagChatDTO`、`KnowledgeBaseDTO`、`UserDTO`），源码文件数减少 80%。

---

## 环境配置

### 数据库

```sql
-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 修复时间字段默认值
ALTER TABLE chat_messages ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE chat_sessions ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE chat_sessions ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
```

### 环境变量

```powershell
# DeepSeek API Key
setx DEEPSEEK_API_KEY "sk-xxx"

# DashScope API Key（用于 Embedding）
setx DASHSCOPE_API_KEY "sk-xxx"
```

### 启动命令

```powershell
# demo 后端
cd D:\Javacode\demo
export JAVA_HOME="D:/jdk26"
./mvnw spring-boot:run

# inter 前端
cd D:\Javacode\interview-guide-ref\frontend
pnpm install
pnpm dev
```

---

## 代码结构

### demo 项目结构

```
demo/
├── src/main/java/com/example/demo/
│   ├── ai/                          # AI 网关
│   │   ├── LlmProviderProperties.java
│   │   ├── LlmProviderRegistry.java
│   │   └── ApiPathResolver.java
│   ├── controller/                  # 控制器
│   │   ├── ChatController.java
│   │   ├── UserController.java
│   │   └── KnowledgeBaseController.java
│   ├── service/                     # 服务层
│   │   ├── ChatService.java
│   │   ├── ChatResilienceService.java
│   │   ├── ChatSessionService.java
│   │   ├── UserService.java
│   │   ├── JwtService.java
│   │   ├── KnowledgeBaseService.java
│   │   └── FileParseService.java
│   ├── entity/                      # 实体类
│   ├── dto/                         # 数据传输对象
│   ├── mapper/                      # MyBatis Mapper
│   ├── config/                      # 配置类
│   ├── exception/                   # 异常处理
│   ├── filter/                      # 过滤器
│   ├── security/                    # 安全相关
│   └── tool/                        # 工具类
├── src/main/resources/
│   ├── application.yml              # 配置文件
│   ├── mapper/                      # MyBatis XML
│   └── static/                      # 静态资源
└── pom.xml
```

### inter 前端结构

```
interview-guide-ref/frontend/
├── src/
│   ├── api/                         # API 客户端
│   │   ├── request.ts               # Axios 封装
│   │   ├── stream.ts                # SSE 流式请求
│   │   ├── ragChat.ts               # RAG 聊天 API
│   │   └── knowledgebase.ts         # 知识库 API
│   ├── components/                  # 组件
│   │   ├── Layout.tsx               # 布局组件
│   │   └── LoginDialog.tsx          # 登录对话框
│   ├── pages/                       # 页面
│   │   └── KnowledgeBaseQueryPage.tsx
│   └── types/                       # 类型定义
├── vite.config.ts                   # Vite 配置
└── package.json
```

---

## 学习收获

### 1. Spring AI 架构

- **LlmProviderRegistry**：AI 网关，统一管理所有供应商
- **惰性缓存**：ConcurrentHashMap + computeIfAbsent
- **配置驱动**：加新供应商 = 加配置，业务代码不用改

### 2. RAG 技术栈

- **向量数据库**：pgvector（PostgreSQL 扩展）
- **Embedding 模型**：DashScope text-embedding-v3
- **文本分块**：TokenTextSplitter
- **语义搜索**：VectorStore.similaritySearch()

### 3. 前后端对接

- **API 路径对齐**：统一接口规范
- **JWT 认证**：请求拦截器自动添加 token
- **SSE 流式**：纯文本格式，前端直接解析

### 4. 问题排查

- **日志分析**：通过后端日志定位问题
- **浏览器调试**：F12 网络请求和控制台
- **代码审查**：检查数据格式和类型匹配

---

## 总结

通过两天的学习，完成了：

1. **AI 网关重构**：统一 OpenAI 兼容协议，简化代码
2. **RAG 知识库功能**：完整的知识库管理、向量化、语义搜索、RAG 问答
3. **前后端对接**：API 路径对齐、JWT 认证、SSE 流式

下一步可以继续完善：
- 异步向量化（Redis Stream）
- 更多前端功能
- 性能优化和监控

---

*文档生成时间：2026-08-23*
*项目状态：开发中*
