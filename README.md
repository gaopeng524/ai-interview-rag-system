# 🤖 AI-Agent-Interview-RAG (智能面试与知识库辅导系统)

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0%20GA-blue.svg)
![React](https://img.shields.io/badge/React-18.x-61dafb.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20(pgvector)-336791.svg)
![Redis](https://img.shields.io/badge/Redis-7.x%20Stream-dc382d.svg)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ed.svg)
![License](https://img.shields.io/badge/License-MIT-green.svg)

**基于 Modular RAG 架构与多模型高可用网关的企业级 AI 模拟面试、知识库问答与简历智能诊断系统**

[系统架构](#-系统架构) • [核心技术亮点](#-核心技术亮点) • [功能矩阵](#-功能矩阵) • [快速启动](#-快速启动) • [目录结构](#-目录结构)

</div>

---

## 📖 项目简介

**AI-Agent-Interview-RAG** 是一套面向企业真实生产标准打造的全栈 AI 智能求职与知识库辅导系统。系统深度整合 **Spring AI 2.0.0 GA**、**PostgreSQL (pgvector)** 与 **Redis Stream**，不仅实现了全流程的“简历诊断 ➔ 知识库考题提炼 ➔ 全真模拟面试 ➔ STAR 法则复盘 ➔ 日程管理”，更在底层落地了**学术级 Modular RAG 流水线**、**多供应商大模型毫秒级主备级联容灾网关**、**流式推理 Token 成本精准计量**以及**动态 UUID 边界 Prompt 防越狱体系**。

---

## 🏛️ 系统架构

### 1. 全局分层架构图

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              前端交互层 (React 18 + Vite + TailwindCSS)                │
 │   • 面试日程看板 (Calendar/Timeline)               • 知识库考题提炼与分类刷题工作台   │
 │   • 多轮 RAG 对话 (动态多库勾选/打字机)            • 全真模拟面试 (STAR评分/雷达图)   │
 │   • 简历六维诊断 (秒传去重/STAR润色)               • 多模型控制台 (在线热切/Token大盘) │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │ HTTP RESTful / SSE (Server-Sent Events)
 ┌───────────────────────────────────────────▼────────────────────────────────────────────┐
 │                              API 网关安全与前置防御层                                   │
 │   • Spring Security + JWT 无状态鉴权               • Redis 令牌桶接口限流 (@RateLimit) │
 │   • PromptSanitizer 防越狱净化器                   • 动态不可预测 8 位 UUID 边界包裹   │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │
 ┌───────────────────────────────────────────▼────────────────────────────────────────────┐
 │                              核心业务编排与 Agent 流程层                                │
 │  ┌─────────────────────────┐ ┌───────────────────────────┐ ┌─────────────────────────┐ │
 │  │      模拟面试核心模块    │ │       知识库与考题模块     │ │       简历深度审计模块  │ │
 │  │  • 动态试题生成         │ │  • 多格式解析 (PDF/Word)  │ │  • SHA-256 哈希秒传   │ │
 │  │  • 逐题对答与实时简评   │ │  • 考题分类聚类提炼       │ │  • 六维雷达图打分     │ │
 │  │  • STAR 综合复盘报告    │ │  • 刷题流转与容量度量     │ │  • iText 7 PDF 报告导出│ │
 │  └─────────────────────────┘ └───────────────────────────┘ └─────────────────────────┘ │
 │  ┌─────────────────────────┐ ┌───────────────────────────┐ ┌─────────────────────────┐ │
 │  │      多轮长会话记忆模块  │ │       日程智能提取模块     │ │       异步任务削峰引擎  │ │
 │  │  • 滑动窗口活跃记忆     │ │  • 腾讯/飞书/Zoom 正则提取│ │  • Redis Stream 消息队列│ │
 │  │  • 增量滚动摘要压缩     │ │  • LLM 非标语义结构化解析 │ │  • SETNX 5分钟排重锁   │ │
 │  └─────────────────────────┘ └───────────────────────────┘ └─────────────────────────┘ │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │
 ┌───────────────────────────────────────────▼────────────────────────────────────────────┐
 │                          Modular RAG 检索增强体系 (Ragas 89.9 分)                      │
 │  [用户提问] ➔ [QueryExpander 意图多路扩展] ➔ [pgvector 向量召回 (双键兼容+候选池宽检索)]  │
 │     ➔ [RagReranker 大模型交叉语义精排] ➔ [Parent-Child 父子块级联还原] ➔ [上下文组装]   │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │
 ┌───────────────────────────────────────────▼────────────────────────────────────────────┐
 │                         多供应商大模型统一网关 (High-Availability Gateway)              │
 │   • 主备级联自动熔断降级链：小米 MiMo-V2.5-Pro ➔ 通义千问 Qwen ➔ 智谱 GLM-4 ➔ DeepSeek  │
 │   • In-Process 全局 Token 成本计量 (LlmCostMeterAdvisor，含推理思考 Token 毫秒级捕获)   │
 │   • ApiKey 动态轮询负载均衡池 (ApiKeyPool) + 四级防弹结构化解析 (AiJsonUtils)          │
 └───────────────────────────────────────────┬────────────────────────────────────────────┘
                                             │
 ┌───────────────────────────────────────────▼────────────────────────────────────────────┐
 │                              基础设施与持久化存储层                                    │
 │   • PostgreSQL 16 + pgvector (业务数据表 + 1536/1024 维高维向量索引 HNSW/IVFFlat)     │
 │   • Redis 7 (Stream 异步解耦队列 + 分布式防重锁 + 会话短期缓存)                        │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Modular RAG 深度检索工作流

```text
               ┌───────────────────────┐
               │    用户原始提问 Query │
               └───────────┬───────────┘
                           │
                           ▼
          ┌──────────────────────────────────┐
          │  Step 1: QueryExpander 意图改写  │ ➔ 提炼出 2~3 个正交搜索子短语
          └────────────────┬─────────────────┘
                           │
                           ▼
          ┌──────────────────────────────────┐
          │  Step 2: pgvector 双重候选召回   │ ➔ 双重元数据 (kb_id / knowledge_base_id)
          └────────────────┬─────────────────┘   配合内存安全回退机制过滤
                           │
                           ▼
          ┌──────────────────────────────────┐
          │  Step 3: RagReranker 交叉语义精排 │ ➔ LLM 相关性评分过滤 (<0.4 剔除)
          └────────────────┬─────────────────┘
                           │
                           ▼
          ┌──────────────────────────────────┐
          │  Step 4: Parent-Child 上下文还原 │ ➔ 微子块命中，无缝替换为 750 Tokens 父块
          └────────────────┬─────────────────┘   并注入面包屑链路 (Category > Doc > Section)
                           │
                           ▼
          ┌──────────────────────────────────┐
          │  Step 5: 动态 UUID 边界与安全组装│ ➔ <data-boundary-{UUID}> 隔离
          └────────────────┬─────────────────┘   挂载 ANTI_INJECTION 铁律进入 LLM
```

---

## 🚀 核心技术亮点

### 1. 🏆 学术级 Modular RAG 检索流水线（89.9 Ragas 综合高分）
* **父子级联切片 (Parent-Child Chunking)**：针对长文档“检索精度”与“上下文完整性”的天然矛盾，采用 **子块 180 Tokens（负责高敏感向量召回）+ 父块 750 Tokens（负责向 LLM 注入完整上下文）**，配合面包屑层级标签，彻底消灭孤立碎片断章取义问题。
* **意图多路扩展 (`QueryExpander`)**：将用户模糊或短文本问题通过 LLM 拆解并重构为 2~3 个具象化技术短语，大幅降低漏检率。
* **语义交叉精排 (`RagReranker`)**：在初筛 Top-N 候选池上，调用轻量大模型执行严格相关度重排与阈值过滤，阻断噪声切片进入上下文。
* **元数据双重对齐与内存安全回退**：数据库前置 `FilterExpression` 与应用层内存 `isDocInKnowledgeBases` 双轨容灾，防范底库 JSONB 类型转换差异造成的检索误杀。

### 2. 🛡️ 多供应商主备自动级联降级网关（99.99% 服务可用性）
* **智能链路故障转移**：构建 **`Xiaomi MiMo-V2.5-Pro` ➔ `DashScope 通义千问` ➔ `智谱 GLM-4-Flash` ➔ `DeepSeek-V3/R1`** 的级联降级链。通过响应式流 `Reactor.onErrorResume` 捕获异常，毫秒级无感降级到备用渠道，抵御云端大模型偶发 500、限流 429 或网络超时。
* **URL 路径自适应修复 (`ApiPathResolver`)**：自动识别并平滑纠正各供应商 `/v1` 尾部路径拼接异常。
* **动态 Key 轮询池 (`ApiKeyPool`)**：支持多 API Key 轮询负载均衡与故障剔除。

### 3. 📊 流式推理 Token 成本计量与国内定价精准对齐
* **全景 Token 拦截体系 (`LlmCostMeterAdvisor`)**：
  * **流式 SSE 深度监听**：在 `createStreaming` 分块传输中开启 `stream_options.include_usage`，解决前端流式打字机无法统计 Token 的行业痛点；
  * **思考 Token 零损捕获**：针对 DeepSeek-R1、Xiaomi MiMo 等推理模型的 `reasoning_tokens`（思考过程），采用 `Math.max(completion, total - prompt)` 动态补正，彻底杜绝隐藏消耗漏记。
* **官方阶梯定价实时核算**：动态对齐小米官方 Domestic Pricing 标准（未命中缓存 ￥3.00/M、输出 ￥6.00/M），支持将单次花费精确记录至微元（0.000001 元）并实时滚入全局总账单。

### 4. 🔒 工业级 Prompt 防越狱与动态 UUID 边界防御（AI 安全装甲）
* **三层立体防注入体系 (`PromptSanitizer`)**：
  1. **输入正则去害化**：屏蔽行首伪造角色标记（`system:`、`assistant:`）与经典指令覆盖短语（`ignore all previous instructions`、`你不再是`、`DAN模式`）；
  2. **不可预测随机 UUID 边界包裹**：使用 `<data-boundary-{UUID前8位}-{label}> ... </data-boundary-...>` 隔离外部不可信数据。攻击者物理上无法推算当次 8 位 UUID，从根本上封死闭合标签逃逸漏洞；
  3. **系统提示词绝对准则**：在 System Prompt 尾部追加最高优先级安全铁律，勒令解码器将边界内的所有文字纯粹作为“待分析数据”，严禁执行任何伪造指令。

### 5. ⚡ 四级防弹结构化输出治理 (`AiStructuredClient`)
* **简单任务消灭字段名**：针对改写、标签提取等任务，直接约束大模型输出纯 JSON 数组 `[...]`，消除模型改动字段名带来的 Jackson 解析灾难；
* **强契约 Java Record DTO**：复杂业务（面试报告、试题生成）通过严谨强类型 Record 接收；
* **解码器硬约束**：下发各大模型厂商全兼容的 `response_format: {"type": "json_object"}`（JSON Mode）；
* **清洗脱壳与优雅降级**：`AiJsonUtils` 自动剥离 Markdown 围栏反引号与首尾废话；遇极端故障自动触发 `FallbackSupplier` 毫秒级兜底，保障界面永不白屏报 500。

### 6. 🧠 长会话动态增量滚动摘要 (`ChatMemoryService`)
* **`ConversationSummaryBufferMemory` 引擎**：兼顾近期对话细节与长期前情背景。
* 当会话轮数超出滑动窗口（>= 3 轮），后台自动通过异步线程增量提炼出 80~150 字前情提要并缓存至 Redis，解决长对话超出上下文窗口与 Token 暴增问题。

### 7. 🚦 Redis Stream 异步化解耦与分布式防重排斥锁
* **文档向量化与面试评估削峰**：大文档解析、Embedding 向量化、试卷评估打分等重型任务全量接入 **Redis Stream 消息队列**，配合 `XACK` 确认机制，保障任务不丢失；
* **分布式防重复点击锁**：通过 Redis `SETNX` 建立带 5 分钟 TTL 防死锁保护的业务互斥锁，彻底杜绝前端快速连击导致的大模型重复调用与重复扣费。

---

## 📦 功能矩阵

| 业务模块 | 核心能力描述 |
| :--- | :--- |
| **全真模拟面试** | 岗位与技术方向分级出题、逐题作答对答、考官即时简评、STAR 法则综合复盘打分、能力六维雷达图。 |
| **知识库考题提炼** | 上传技术文档/面经，自动提炼分类题库（包含标准答案、评分细则、递进式追问 Follow-up），支持模拟面试直接关联刷题。 |
| **简历深度技术诊断** | 支持 PDF/Word 解析、**SHA-256 文件指纹秒传复用**、六维技术穿透式打分、基于 STAR 法则的项目经历改写润色示范。 |
| **多轮 RAG 知识库问答** | 支持单会话自由勾选多个知识库、多路意图扩展检索、打字机流式输出、推理模型思考过程折叠展示。 |
| **面试日程智能管理** | 飞书/腾讯会议/Zoom 邀约文本一键智能粘贴提取（会议号、密码、时间）、日历与时间线双重视图、定时过期流转。 |
| **PDF 报告导出引擎** | 基于 iText 7 内置嵌入开源可商用 `朱雀仿宋` 矢量字体，支持跨平台 Docker/Linux 导出高清排版无乱码的面试评估 PDF 报告。 |
| **AI 网关大盘与控制台** | 实时多供应商配置热重载、在线连通性测试、输入/输出/推理 Token 全量大盘监控、累计花费精确统计。 |

---

## 🛠️ 技术栈清单

### 后端架构
* **核心框架**：Java 21 / Spring Boot 3.4.3
* **AI 基础设施**：Spring AI 2.0.0 GA / OpenAI 官方 Java SDK
* **数据库 & 向量存储**：PostgreSQL 16 + **pgvector**
* **缓存与消息中间件**：Redis 7-Alpine (Lettuce + **Redis Stream**)
* **持久层与工具库**：MyBatis 3.0 / Lombok / Jackson JSR310 / Apache POI / Apache PDFBox / iText 7

### 前端架构
* **核心框架**：React 18 / TypeScript / Vite 5
* **样式与动画**：TailwindCSS / Framer Motion / Lucide React
* **长列表与渲染**：React Virtuoso / React Markdown / Remark GFM

---

## 🚀 快速启动

### 方式一：Docker Compose 一键全栈部署（推荐）

项目已完成全量容器化编排，包含 PostgreSQL (带 pgvector 扩展)、Redis 7、后端核心服务与前端 Nginx 反向代理。

1. **克隆项目并进入根目录**：
   ```bash
   git clone https://github.com/your-username/AI-Agent-Interview-RAG.git
   cd AI-Agent-Interview-RAG
   ```

2. **配置环境变量**：
   复制根目录下的 `.env.example` 为 `.env`，填入你的大模型 API Key（小米 mimo / 通义千问 / 智谱 / DeepSeek 任选其一即可）：
   ```bash
   cp .env.example .env
   ```

3. **一键启动全栈集群**：
   ```bash
   docker-compose up -d --build
   ```

4. **访问系统**：
   * 浏览器访问：`http://localhost` (前端 Nginx 默认监听 80 端口，自动反向代理 API 请求至 8080)；
   * 后端健康检查：`http://localhost:8080/actuator/health`。

---

### 方式二：本地开发环境启动

#### 1. 启动基础设施
确保本地已安装并运行 PostgreSQL (需启用 `vector` 插件) 与 Redis：
* PostgreSQL：`localhost:5432/ai_chat` (账号: `postgres`, 密码: `123456`)
* Redis：`localhost:6379`

#### 2. 启动后端服务
```bash
# 进入项目根目录
./mvnw clean spring-boot:run
```
后端默认启动在 `http://localhost:8080`，数据库表结构与预置模型配置由 `DatabaseInitConfig` 自动完成初始化。

#### 3. 启动前端项目
```bash
cd frontend
pnpm install
pnpm dev
```
前端默认启动在 `http://localhost:5173`。

---

## 📂 目录结构

```text
AI-Agent-Interview-RAG/
├── docker/                             # Docker 初始化挂载脚本 (SQL 等)
├── frontend/                           # React 18 前端单页应用
│   ├── src/
│   │   ├── api/                        # API 请求与 SSE 流式解析封装
│   │   ├── components/                 # 通用 UI 组件 (代码高亮/音频录制等)
│   │   ├── pages/                      # 页面 (面试/知识库/简历/日程/设置)
│   │   └── types/                      # TypeScript 类型契约
│   ├── Dockerfile                      # 前端多阶段构建 Dockerfile
│   └── nginx.conf                      # Nginx 反向代理与路由配置文件
├── src/                                # Spring Boot 后端工程
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   ├── common/                 # 全局通用底座
│   │   │   │   ├── ai/                 # AI 核心安全组件 (PromptSanitizer, AiStructuredClient)
│   │   │   │   ├── config/             # 系统与数据库初始化配置 (DatabaseInitConfig)
│   │   │   │   └── util/               # 实用工具类 (AiJsonUtils, PdfExportService)
│   │   │   └── modules/                # 业务领域高内聚模块
│   │   │       ├── chat/               # RAG 多轮对话会话与长记忆管理
│   │   │       ├── interview/          # 模拟面试生成、逐题评估与复盘
│   │   │       ├── interviewschedule/  # 面试日程管理与双模智能邀约提取
│   │   │       ├── knowledgebase/      # 知识库管理、向量检索与考题提炼
│   │   │       ├── llmprovider/        # 统一 AI 网关核心 (主备降级/成本计量/Key池)
│   │   │       ├── rag/                # Modular RAG 流水线 (父子块/改写/重排/评测)
│   │   │       └── resume/             # 简历解析、SHA-256秒传与STAR诊断
│   │   └── resources/
│   │       ├── fonts/                  # 内置朱雀仿宋商用免费中文字体
│   │       ├── mapper/                 # MyBatis XML 映射文件
│   │       └── application.yml         # 核心配置文件
│   └── test/                           # 全量单元测试与 RAG 真实项目度量基准测试
├── docker-compose.yml                  # 全栈生产级 Docker 编排文件
├── Dockerfile                          # 后端 Maven 多阶段构建镜像定义
├── 代码学习.md                          # 57 个架构专题研发与技术细节深度解析手记
└── pom.xml                             # Maven 依赖清单
```

---

## 🧪 测试与质量保障

系统内置完备的单元测试与学术评测套件，覆盖核心容灾、数据安全、算法与业务闭环：

```bash
# 运行全量单元测试
./mvnw test

# 运行 Prompt 防越狱与注入专用测试套件
./mvnw test -Dtest=PromptSanitizerTest

# 运行真实生产 RAG 场景四维学术基准评测
./mvnw test -Dtest=RealProjectRagBenchmarkTest
```

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源。欢迎 Star、Fork、提交 Issue 或 PR！