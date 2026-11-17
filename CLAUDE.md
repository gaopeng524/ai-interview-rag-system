# 🤖 AI 智能面试与知识库辅导系统 —— 研发与架构全景档案 (CLAUDE.md)

> 本文档为本系统最权威、最完整的系统架构与技术迭代全景档案。  
> 包含系统从 0 到 1 的所有核心设计、每一步的技术优化与重构细节、踩坑与解决方案、数据库表结构以及全量测试清单。  
> **原则**：后续开发只在相应章节做增量追加，不删除任何历史优化细节。

---

## 🏛️ 系统全局架构图

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │                         前端视图层 (React 18 + Vite)                          │
 │  - 面试日程中心 (Calendar / Timeline)     - 知识库考题提炼与刷题工作台        │
 │  - 多轮 RAG 智能问答持久化会话             - 简历深度诊断与模拟面试评估       │
 │  - 全局设置中心 (多模型热切换/测试)       - AI 聊天助手工作台 (SSE流式)      │
 └──────────────────────────────────────┬───────────────────────────────────────┘
                                        │ RESTful / SSE 流式通信
 ┌──────────────────────────────────────▼───────────────────────────────────────┐
 │                           Spring Boot 核心业务层                             │
 │  ├── 1. 多模型统一接入网关 (LlmProviderRegistry & ChatResilienceService)     │
 │  ├── 2. 简历智能深度诊断系统 (ResumeService & SHA-256秒传 & STAR法则)        │
 │  ├── 3. 全真模拟面试与考官评估 (InterviewService & AI考官综合评分)           │
 │  ├── 4. 面试日程智能管理系统 (InterviewSchedule & 正则+LLM双模解析)          │
 │  ├── 5. 知识库/面经提炼考题 (KnowledgeBaseQuestion & pgvector语义召回)       │
 │  ├── 6. 异步任务与防重引擎 (Redis Stream 消息队列 & 分布式排重锁)            │
 │  └── 7. 跨平台 PDF 报告导出引擎 (iText 7 + 朱雀仿宋字体内置嵌入)             │
 └──────────────────────────────────────┬───────────────────────────────────────┘
                                        │
 ┌──────────────────────────────────────▼───────────────────────────────────────┐
 │                         底层基础设施与持久化存储                              │
 │  - PostgreSQL 16 (pgvector 语义向量库 + 业务关系表)                           │
 │  - Redis 7 (Stream 异步解耦队列 + SETNX 分布式排重锁 TTL 保护)                │
 │  - 多模型网关 (小米 mimo、DeepSeek、阿里通义千问、智谱 GLM)                   │
 └──────────────────────────────────────────────────────────────────────────────┘
```

---

## 📅 阶段演进与全量优化细节记录

---

### 🌟 阶段一：系统基础底座搭建与多模型网关 (Day 1)

#### 1. 多供应商大模型统一接入网关 (`LlmProviderRegistry`)
* **协议统一接入**：所有大模型（小米 mimo-v2.5-pro、DeepSeek-V3/R1、阿里通义千问 Qwen-Turbo / Text-Embedding-v3、智谱 GLM-4 等）统一抽象为 OpenAI 兼容协议。
* **Spring AI 2.0.0 GA 适配**：从 M4 版本的 `OpenAiApi` 升级迁移至 Spring AI 2.0.0 GA 的官方 `OpenAIClient` 架构。
* **URL 动态修复 (`ApiPathResolver`)**：自动兼容不同模型厂商 `/v1` 尾部斜杠，防止出现 `//chat/completions` 双斜杠路径异常。
* **按需惰性初始化 (Lazy Initialization)**：基于 `ConcurrentHashMap` 缓存机制，应用启动时不盲目建立所有厂商连接，仅在首次使用或前端测试时按需创建客户端，大幅降低内存开销。
* **配置持久化与在线热重载**：基于 `llm_provider_config` 表与 `LlmProviderConfigService`，支持前端在【设置中心】自由切换默认模型与修改 API Key，实时生效无需重启 JVM。

#### 2. 大模型调用韧性与容灾降级 (`ChatResilienceService`)
* **受控重试机制**：禁用 Spring AI 默认 10 次盲目重试，限制最大重试 2 次并配合指数退避策略。
* **智能错误甄别**：精准区分客户端参数错误（4xx 不重试）与网络抖动/服务不可用（5xx / 超时自动重试）。
* **多模型自动熔断与降级**：主模型异常时支持自动降级至备用模型，保障核心流程不中断。

#### 3. RAG 知识库与向量检索基础设施
* **PostgreSQL + pgvector**：建立 `knowledge_bases` 与 `vector_store` 表结构，支持文档分块（Chunking）与高维向量相似度检索（Cosine Distance）。
* **异步向量化流水线**：接入 Redis Stream (`stream:kb:vectorize`) 实现文档解析入库与向量化的异步解耦。

#### 4. 安全认证与流式打字机通信
* **安全体系**：基于 Spring Security + JWT 实现无状态用户认证与接口鉴权。
* **SSE 打字机**：基于 Spring WebFlux / `ServerSentEvent` 实现低延迟流式打字机响应。

---

### 🌟 阶段二：全真模拟面试引擎与简历深度诊断 (Day 2)

#### 1. 简历智能深度诊断系统 (`ResumeService`)
* **文件指纹去重（SHA-256 秒传机制）**：上传时计算文件 SHA-256 哈希值，若数据库已存在历史诊断记录则直接秒传并复用结果，杜绝重复计算与 Token 浪费。
* **多格式文件智能解析**：支持 PDF (`PdfBox` / `iText`) 与 TXT 文本提取，自动过滤非法字符与乱码。
* **六维雷达评分**：从基本信息 (15分)、教育背景 (10分)、工作经历 (25分)、项目深度 (25分)、专业技能 (15分)、排版规范 (10分) 结构化打分。
* **STAR 法则改写建议**：大模型自动提炼项目中的情境 (Situation)、目标 (Task)、行动 (Action)、结果 (Result)，给出逐条深度优化建议。

#### 2. 全真模拟面试与 AI 考官评估 (`InterviewService`)
* **真题动态生成**：结合候选人简历内容、目标岗位 JD 与技术栈知识库，由大模型动态生成标准考题。
* **多轮对答与实时反馈**：支持逐题作答、即时追问与考官实时要点反馈（仅给简评，不剧透后续题目）。
* **综合面试报告**：面试结束后自动生成雷达图能力评估（专业知识、逻辑思维、项目经验、沟通表达等）与详细改进建议。

#### 3. 多轮 RAG 智能问答工作台 (`ChatController` / `/api/rag-chat`)
* **会话全生命周期管理**：支持新建会话、会话列表拉取、重命名会话标题、置顶会话。
* **多知识库联合检索**：支持在单个会话中自由勾选多个知识库，由 `pgvector` 联合召回最相关片段并结合上下文连续追问。

---

### 🌟 阶段三：异步化升级、日程管理、题库提炼与防重锁 (Day 3 / 2026-08-25)

#### 1. 知识库/面经智能提炼考题与题库管理系统（新建核心功能）
* **数据表结构与持久层**：
  * 在 [`DatabaseInitConfig.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/common/config/DatabaseInitConfig.java) 中设计并自动创建 `knowledge_base_questions` 表与索引 (`idx_kb_questions_kb_id`, `idx_kb_questions_category`)。
  * 建立实体 [`KnowledgeBaseQuestionEntity.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/knowledgebase/model/entity/KnowledgeBaseQuestionEntity.java) 与状态枚举 (`DRAFT`, `ACTIVE`, `ARCHIVED`, `STALE`)。
  * 编写 MyBatis Mapper 接口与 XML，支持题目批量插入、分类聚类统计 (`countCategoriesByKbId`)、多维条件筛选及全生命周期流转。
* **AI 智能出题引擎**：
  * 编写 [`KnowledgeBaseQuestionGenerationService.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/knowledgebase/service/KnowledgeBaseQuestionGenerationService.java)，自动从 `pgvector` 知识库召回核心段落，利用 `AiStructuredClient` 驱动大模型拆解出包含**题干、分类、难度、标准答案、评分要点与递进式追问（Follow-up）**的 10~20 道标准题库。
* **题库刷题与容量计算**：
  * 编写 [`KnowledgeBaseQuestionService.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/knowledgebase/service/KnowledgeBaseQuestionService.java)，提供分类统计、手动增删改查、状态快捷变更为已掌握以及面试出题容量计算 (`getInterviewCapacity`)。
* **前端接口对齐与直连面试**：
  * 编写 [`KnowledgeBaseInterviewController.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/knowledgebase/controller/KnowledgeBaseInterviewController.java)，100% 对齐前端 `KnowledgeBaseInterviewQuestionsPage.tsx` 所需的 8 个核心接口。

#### 2. 考题提炼双层防重复申请机制（排重锁优化）
* **背景痛点**：避免前端连击或多客户端并发触发大模型生成，造成重复扣费与并发写库。
* **第 1 层（内存状态锁）**：在 `KnowledgeBaseQuestionGenerationService` 中校验 `taskStatusMap`，若状态已为 `PROCESSING`，直接返回当前任务，拒绝重复启动异步线程。
* **第 2 层（Redis 分布式锁）**：在 [`AsyncTaskStreamConstants.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/common/constant/AsyncTaskStreamConstants.java) 中定义 `lock:kb:question:gen:{kbId}`，通过 Redis `SETNX` 加锁并设置 5 分钟 TTL 防死锁保护，任务结束后在 `finally` 中可靠释放锁。

#### 3. 面试日程智能管理全功能模块建设 (`InterviewSchedule`)
* **数据表与持久层**：在 `DatabaseInitConfig.java` 中创建 `interview_schedules` 表及复合索引 (`idx_interview_schedules_user_time`, `idx_interview_schedules_status`)，建立实体与 MyBatis 映射。
* **双模智能邀约解析引擎 (`InterviewParseService.java`)**：
  * **快速正则流**：支持飞书日程、腾讯会议（自动正则提取 9 位会议号与数字/字母密码）、Zoom 链接与密码；
  * **大模型结构化流**：覆盖非标准或自然语言邀约文本；
  * **多格式日期兼容**：适配 ISO 8601、中文年月日时分、斜杠 `yyyy/MM/dd` 与连字符 `yyyy-MM-dd`。
* **定时过期状态自动流转 (`ScheduleStatusUpdater.java`)**：基于 `@Scheduled` 每小时自动扫描过期未操作日程，更新状态为 `CANCELLED`。
* **REST 控制器 (`InterviewScheduleController.java`)**：提供日程 CRUD、状态更新、时间范围筛选与文本智能解析接口。

#### 4. 面试评估异步解耦与 Redis Stream 分布式锁改造
* **从内存锁升级为分布式锁**：彻底清理老旧的单机内存并发 Set (`inFlightEvaluations`)，全面改用 Redis Stream 消息队列（`stream:interview:evaluate`）与 Redis 分布式排重锁（`lock:interview:evaluate:{sessionId}`，TTL 5分钟）。
* **可靠消费保障**：编写 [`InterviewEvaluateStreamProducer.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/interview/async/InterviewEvaluateStreamProducer.java) 与 [`InterviewEvaluateStreamConsumer.java`](file:///d:/Javacode/demo/src/main/java/com/example/demo/modules/interview/async/InterviewEvaluateStreamConsumer.java)，实现基于 `XACK` 确认机制的可靠消费，并在 `finally` 块中安全释放排重锁。

#### 5. PDF 导出中文字体与排版彻底攻坚
* **解决中文方块与跨平台乱码**：在 `src/main/resources/fonts/` 中引入开源商用免费字体 `ZhuqueFangsong-Regular.ttf`（朱雀仿宋），通过 `PdfFontFactory.createFont` 将字体嵌入 PDF，确保在 Windows / Linux / macOS / Docker 容器下导出无乱码。
* **美化排版与结构呈现**：优雅排版面试综合得分、六维能力评估雷达图、各考题打分及深度改进建议。

#### 6. 核心 Bug 排查与系统稳固性修复
* **Jackson Java 8 LocalDateTime 序列化修复**：引入 `jackson-datatype-jsr310` 依赖，统一集中配置于 `RedisConfig.java`，删除冲突的 `JacksonConfig.java`。
* **路由冲突修复**：修复 `KnowledgeBaseInterviewController` 与 `InterviewController` 在 `POST /api/knowledgebase-interviews/sessions` 上的 Ambiguous 映射冲突。
* **平稳启动验证**：确保后台端口释放，Tomcat 在 3 秒内完成全量组件初始化。

---

### 🌟 阶段四：全栈 Docker 容器化部署与工程结构整合 (Day 4 / 2026-08-26)

#### 1. 前后端工程结构一体化整合
* 将前端完整工程（React 18 + Vite + TailwindCSS + Lucide Icons）迁移整合至 `demo/frontend`；
* 形成清晰的标准全栈架构：
  * `demo/src`：Java 26 / Spring Boot 3.3 / Spring AI 2.0 GA 后端；
  * `demo/frontend`：React 18 前端 SPA 应用；
  * `demo/docker-compose.yml`：全栈容器化统筹编排。

#### 2. 前端多阶段构建与 Nginx 反向代理 (`demo/frontend/Dockerfile` & `nginx.conf`)
* **第一阶段 (Node.js 20-alpine Builder)**：基于 `package.json` 与 `pnpm-lock.yaml` 缓存层快速安装依赖并执行 `pnpm build` 生成 `dist` 静态资源；
* **第二阶段 (Nginx Alpine)**：基于极简轻量 Nginx 镜像托管静态资源，暴露 `80` 端口；
* **Nginx 路由与反向代理策略**：
  * 单页应用路由支持：`try_files $uri $uri/ /index.html;`，彻底解决前端刷新 404；
  * API 反向代理：将 `/api/` 路由透明代理至后端容器 `http://app:8080`；
  * AI 超时与文件上传优化：设置 `proxy_read_timeout 300s;`（支持 AI 长文本与流式打字机）、`client_max_body_size 50M;`（支持大文档与简历上传）。

#### 3. 后端 Maven 多阶段构建 (`demo/Dockerfile`)
* **第一阶段 (Temurin 25 JDK Builder)**：利用 Maven Wrapper 与依赖离线缓存快速执行 `clean package -DskipTests`；
* **第二阶段 (Temurin 25 JRE Runtime)**：使用精简 JRE 运行 `app.jar`，开启 `-XX:+UseContainerSupport` 容器内存自适应感知与 G1GC。

#### 4. 全栈统一容器编排 (`demo/docker-compose.yml`)
* 统一一键编排 4 大服务：
  1. `interview-postgres`：`pgvector/pgvector:pg16` 数据库（挂载 `docker/postgres/init.sql` 自动启用 vector 插件与数据卷持久化）；
  2. `interview-redis`：`redis:7-alpine`（支持 Stream 队列与分布式排重锁）；
  3. `interview-app`：后端 Spring Boot 核心服务（健康检查依赖 postgres 与 redis）；
  4. `interview-frontend`：前端 Nginx 代理服务（端口映射 `80:80`）。

#### 5. Docker 全栈容器化部署验证与就绪
* 全量容器状态检查：
  * `interview-frontend` (Port 80): Nginx 静态托管 + API 反向代理 ➔ 状态 `Up`，HTTP 200 OK；
  * `interview-app` (Port 8080): Spring Boot 3.3 核心服务 ➔ 状态 `Up`，HTTP 200 OK；
  * `interview-postgres` (Port 5432): pgvector 向量数据库 ➔ 状态 `healthy`；
  * `interview-redis` (Port 6379): Redis 7-alpine ➔ 状态 `healthy`；
* 浏览器访问 `http://localhost` 即可体验一站式完整 AI 智能面试与知识库辅导系统。

#### 6. AI 供应商配置严格模式与单一可信数据源演进
* **优化**：移除 `LlmProviderRegistry` 在数据库未配置 Key 时隐式借用 `application.yml` 开发者私有 Key 的隐患逻辑；
* **规范**：数据库作为运行时的唯一事实来源。若数据库中未填 API Key，前端测试连接时直接显式拦截并友好提示：“未配置 API Key，请先填写并保存后再测试连接”，坚决杜绝越权偷跑个人额度。

---

### 🌟 阶段五：安全加固、角色权限与消息可靠消费 (2026-11)

#### 1. 鉴权与资源归属
* **接口必须登录**：去掉 `/api/resumes/**`、`/api/voice-interview/**` 的匿名放行，只有 `GET /api/interview/skills/**` 公开。
* **不再回退到默认用户**：Controller 统一通过 `CurrentUser.requireId(principal)` 取用户，拿不到直接 401（原来会当成 1 号用户）。
* **归属校验**：简历、面试会话、知识库及其考题按 id 访问时一律校验归属；userId 为空、资源无归属人或不是本人均返回 403，且先判断身份再查库。
* **知识库按用户隔离**：`knowledge_bases.user_id`；列表、统计、去重都限定在本人名下；语义检索和 RAG 问答先解析当前用户可访问的范围，不指定知识库时只检索本人的知识库。删除知识库时同时清理向量切片与考题。
* **用户接口**：`GET /users` 仅管理员；`GET /users/{id}` 仅本人或管理员；注册校验用户名 / 密码长度并拒绝重名。

#### 2. 角色权限
* `users.role`（USER / ADMIN，默认 USER），角色写入 JWT；开启方法级鉴权，模型配置的增删改、切换默认模型、重载仅 ADMIN。
* 注册接口强制 USER，管理员只能通过 SQL 授予：`UPDATE users SET role = 'ADMIN' WHERE username = '...'`（需重新登录生效）。
* JWT 未配置密钥时每次启动生成随机密钥；模型供应商地址做主机白名单校验（防 SSRF）。

#### 3. Redis Streams 超时消息回收
* `AbstractStreamConsumer` 在消费线程里定时 `XAUTOCLAIM` 回收空闲超过 `min-idle-seconds`（默认 60）的未确认消息，`XPENDING` 取投递次数，超过 `max-deliveries`（默认 5）记为失败并 ACK。
* 处理期间用 `XCLAIM ... JUSTID` 续期（不增加投递次数），慢任务不会被其它节点误抢。
* 幂等：面试评估为覆盖式更新 + 已完成跳过；知识库向量化改为按 `kb_id` 先删后写。

#### 4. 数据库迁移
* `src/main/resources/db/migration/*.sql`：幂等脚本，按文件名顺序在启动时由 `DatabaseInitConfig.runMigrations` 执行。
  * `V2__add_user_role.sql`：`users.role` 及取值约束；
  * `V3__knowledge_base_owner.sql`：`knowledge_bases.user_id`，存量数据划给最早注册的用户。

#### 5. 仓库整理
* 学习笔记移入 `docs/`（`代码学习.md`、`xiaomimodel.md`、`obsidian/`），Python 评测脚本移入 `scripts/`；前端只保留 `pnpm-lock.yaml`。
* 项目基于 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide) 二次开发，以 AGPL-3.0 发布（见 `LICENSE` 与 README）。

---

## 🧪 全量自动化测试用例清单

> 本节于 2026-11 按仓库实际情况重写：此前列出的 6 个测试类（`ChatResilienceServiceTest` 等，共 20 个用例）在仓库中并不存在。

直接运行 `mvn test` 即可，不依赖外部服务；需要真实中间件的用例在缺少环境时自动跳过。

| 测试类 | 用例数 | 覆盖内容 | 运行条件 |
| :--- | :---: | :--- | :--- |
| `PromptSanitizerTest` | 3 | Prompt 注入清洗与边界隔离 | 无 |
| `TraceManagerTest` | 3 | 调用链 Trace 的层级、耗时与标签 | 无 |
| `SecurityConfigAccessTest` | 5 | 简历 / 语音面试接口必须登录，401 / 403 / 200 | 无 |
| `ResumeServiceOwnershipTest` | 7 | 简历归属校验 | 无 |
| `InterviewServiceOwnershipTest` | 8 | 面试会话归属校验 | 无 |
| `KnowledgeBaseOwnershipTest` | 10 | 知识库归属校验与检索范围 | 无 |
| `KnowledgeBaseAccessTest` | 4 | 知识库与考题接口的 HTTP 层隔离 | 无 |
| `UserControllerAccessTest` | 3 | 用户接口权限 | 无 |
| `UserServiceRoleTest` | 6 | 注册校验、角色写入 JWT | 无 |
| `LlmProviderControllerAuthorizationTest` | 37 | 模型配置写接口仅 ADMIN | 无 |
| `LlmProviderSecurityTest` | 3 | JWT 密钥与模型地址白名单 | 无 |
| `VectorizeIdempotencyTest` | 2 | 向量化先删后写、已完成跳过 | 无 |
| `StreamReclaimIntegrationTest` | 4 | Streams 超时消息回收、毒消息、慢任务不被抢 | Docker（Testcontainers）或 `TEST_REDIS_HOST` |
| `KnowledgeBaseMapperIntegrationTest` | 1 | Mapper SQL 与 V3 迁移脚本 | `TEST_PG_URL` |
| `RealProjectRagBenchmarkTest` | 1 | RAG 检索与回答质量评测 | `-Drag.benchmark=true`，需 pgvector、Redis、大模型 Key |

---

## 📂 数据库全量表结构字典

1. `users`：用户账号与密码表（`role`：USER / ADMIN）；
2. `llm_provider_config`：大模型供应商配置表（支持动态热重载）；
3. `resumes`：简历表（支持文件哈希秒传）；
4. `resume_analysis_reports`：简历六维诊断与 STAR 建议报告表；
5. `interview_sessions`：模拟面试会话核心表；
6. `interview_questions`：面试考题、回答与考官评分表；
7. `interview_evaluations`：面试综合评估与能力雷达得分表；
8. `interview_schedules`：面试日程管理表；
9. `knowledge_bases`：知识库文档元数据表（`user_id`：归属用户）；
10. `vector_store`：PostgreSQL pgvector 高维语义向量切片表；
11. `knowledge_base_questions`：面经智能提炼考题与题库表；
12. `chat_sessions` / `chat_session_knowledge_bases` / `chat_messages`：多轮 RAG 对话会话与消息历史表。

