# 13 · interview-guide 框架蓝图（要复现的对象）

> 从 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 实际 clone 提取的架构骨架。目标是**照这套结构自己复现**，原项目 clone 在 `D:\javacode\interview-guide-ref` 可随时对照。
> 它和我们项目的同源性：**Spring Boot 4.1 + Spring AI 2.0 + PostgreSQL/pgvector + DeepSeek/千问（OpenAI 兼容）+ SSE**。

## 一、分层总览

```
frontend (React) ──REST/SSE──▶ Controller ──▶ Service ──▶ Repository (JPA)
                                        │           │
                                        ▼           ▼
                              PostgreSQL(pgvector)  Redis Stream(异步)
                                        │
                    ─────────────────────────────────────────────
                    common/        通用能力（横切）
                    infrastructure/ 基础设施
                    modules/        业务模块（每个自包含 MVC，6 个）
                    resources/prompts/  *.st Prompt 模板
```

共 **197 个 Java 文件**。三层职责边界清晰：`common`（通用能力，不碰业务）、`infrastructure`（技术设施，也不碰业务）、`modules`（业务模块，自包含 MVC）。

## 二、三层各装什么

### common/ —— 通用能力（横切，最重要）

| 子包 | 类 | 作用 |
|---|---|---|
| `ai` | `LlmProviderRegistry` | **统一取模型的入口**：`getChatClientOrDefault(provider)`，业务代码不直接 new ChatClient |
| `ai` | `StructuredOutputInvoker` | 结构化输出统一入口，业务代码不复制重试逻辑 |
| `ai` | `PromptSanitizer` / `PromptSecurityConstants` | Prompt 注入防护 |
| `async` | `AbstractStreamProducer` / `AbstractStreamConsumer` | **Redis Stream 异步任务模板**（生产者/消费者） |
| `exception` | `BusinessException` + `ErrorCode` | 业务异常带错误码 |
| `result` | `Result<T>` | 统一响应封装，**禁止直接返回 Entity** |
| `transaction` | `TransactionalExecutor` | 显式事务边界——为了把 LLM 调用排除在事务外 |
| `aspect` + `annotation` | `@RateLimit` | 可复用限流注解 |
| `evaluation` | `UnifiedEvaluationService` | 文字/语音面试共用评估引擎 |

### infrastructure/ —— 技术设施

| 子包 | 作用 |
|---|---|
| `file` | S3（RustFS/MinIO）对象存储 |
| `export` | iText 8 PDF 导出（面试报告） |
| `redis` | Redis 连接封装 |
| `mapper` | MapStruct（Entity→DTO 映射） |

### modules/ —— 6 个自包含业务模块

| 模块 | 文件数 | 干什么 |
|---|---|---|
| **knowledgebase** | 49 | ★RAG 知识库：上传/解析/分块/向量化/检索问答/题库生成 |
| **voiceinterview** | 28 | 语音面试：WebSocket + ASR/TTS |
| **interview** | 27 | 模拟面试：Skill 驱动出题 + 多轮追问 + 评估 |
| **llmprovider** | 17 | 多模型 Provider 动态管理 + API key 加密落盘 |
| **resume** | 16 | 简历上传解析 + 异步分析 |
| **interviewschedule** | 11 | 面试安排：日历 + 提醒 |

## 三、模块内部的分层规矩（每个模块都一样）

```
modules/X/
  ├─ XxxController.java      # 路由 + 校验 + 委托，不写业务
  ├─ model/                  # XxxEntity / XxxDTO / XxxRequest / XxxResponse
  ├─ repository/             # 继承 JpaRepository
  ├─ service/                # 业务编排，@Transactional 只在这层
  ├─ listener/               # 异步消费者（对应 common/async 模板）
  └─ skill/                  # Skill 定义（interview 模块特有）
```

命名后缀规矩：持久化 `XxxEntity`、跨层 `XxxDTO`、请求 `XxxRequest`、响应 `XxxResponse`，不可变对象优先 `record`。

## 四、这个框架最值钱的三条链路

### 链路 1：RAG 知识库（核心中的核心）

**写入侧**（上传 → 可检索）：
```
用户上传文档 → 存 S3 + 存元数据(JPA) → Redis Stream producer 发"向量化"任务
    → consumer 异步消费：
        Apache Tika 解析（PDF/DOCX/TXT）
        → TokenTextSplitter 分块（每块 ~800 tokens）
        → 分批（≤10 个/批，DashScope 限制）vectorStore.add() 写入 pgvector
        → 两阶段提交：先用 pending:kbId:jobId 临时 metadata 写入，
                       全部成功后 promote 成正式 kb_id；失败则清理
```
**两阶段提交是精华**：向量化到一半失败，不会让"残缺知识库"被搜索到。见 `KnowledgeBaseVectorService`。

**读取侧**（问答）：
```
用户问题 → 查询改写（LLM 改写，模板 knowledgebase-query-rewrite.st）
    → similaritySearch(query, kbIds, topK, minScore) 向量检索
    → 按知识库过滤（kb_id in [...]）
    → 把检索到的文档组装进 Prompt（knowledgebase-query-system.st）
    → SSE 流式问答（Flux<ServerSentEvent>）
```

### 链路 2：异步任务（Redis Stream）

```
AbstractStreamProducer（发任务到 Stream）
    → AbstractStreamConsumer（消费、处理、ACK）
    → 失败自动重试
    → 恢复调度器（扫描超时/失败任务，补偿）
    → 消费前校验实体存在，已删除则 ACK 丢弃
```
用于：简历分析、文档向量化、题库生成。**耗时操作绝不进 HTTP 请求线程**。

### 链路 3：SSE 流式（和我们的异同）

`RagChatController.sendMessageStream`：
```
1. 先同步保存用户消息 + 创建 AI 消息占位（拿到 messageId）
2. 返回 Flux<ServerSentEvent<String>>
3. 流式过程中攒 fullContent
4. doOnComplete → 把完整内容写回 AI 消息
5. doOnError → 错误也保存已收到的内容
```
比我们多了一个**"先占位后回填"**：前端能立刻拿到 AI 气泡，不用等服务端主动创建。

## 五、工程规范（AGENTS.md 提炼，复现时照做）

1. **Controller → Service → Repository**，Controller 只路由。
2. **`Result<T>` 统一响应，禁止直接返回 Entity**（防泄漏）。
3. 业务失败用 `BusinessException(ErrorCode, msg)`，禁止裸 `RuntimeException`。
4. Entity→DTO 用 MapStruct，Entity 不出 Controller。
5. **LLM / S3 / 外部 HTTP 不进事务**——用 `TransactionalExecutor` 显式控边界。
6. `@Transactional` 只放 Service 公开方法，避免同类内部调用。
7. Prompt 全放 `resources/prompts/*.st`（StringTemplate），不散在代码里。
8. 取模型统一 `LlmProviderRegistry`，结构化输出统一 `StructuredOutputInvoker`。
9. API key / 密码只放 `.env`，数据库 schema 由 Flyway 管理。
10. 代码 2 空格缩进、构造器注入 + `@RequiredArgsConstructor`。

## 六、和我们项目的对照表（复现的起点）

| 维度 | 我们的 demo | interview-guide |
|---|---|---|
| 统一响应 | 无（裸返回） | `Result<T>` |
| 异常 | BusinessException(仅状态码) | BusinessException + ErrorCode 枚举 |
| 模型获取 | 写死 DeepSeek | `LlmProviderRegistry` 多模型 |
| 持久层 | MyBatis + XML | JPA（概念不变，保留 MyBatis 即可） |
| 流式 | Flux<String> 信封 | Flux<ServerSentEvent> 占位回填 |
| 异步 | 无 | Redis Stream 模板 |
| 记忆 | 双层（窗口+DB） | （更聚焦 RAG 会话） |
| 文档解析 | 无 | Apache Tika |
| 向量库 | pgvector 容器已就位 ✅ | pgvector |
| 测试 | 单测 4 个 | JUnit5+Mockito+AssertJ，中文 @DisplayName |

> 相关：[[00-项目总览]] · [[14-复现路线图]] · [[12-坑与排查]]
