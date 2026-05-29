# Argus — 简历项目经历（可直接复用）

> 最后校验：2026-05-28  
> 用途：**直接复制到中文简历 / 面试自我介绍**  
> 原则：**只写代码能验证的能力，不写无数据支撑的性能指标**  
> 配套阅读：`project-status-summary.md`、`resume-vs-reality-analysis.md`

## 项目名称

- **Argus — 多 Agent 并行的自动化代码审查系统**

## 项目简介

面向研发工作流构建的自动化代码审查系统，结合 RAG 架构与大语言模型，实现代码规范检查、潜在 Bug 分析与修复建议生成。系统通过 GitHub Webhook 接收 PR 事件，自动执行多维度审查并将报告回写为 PR Comment。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 / 框架 | Java 21、Spring Boot 3.5.3、Spring WebFlux |
| AI 框架 | LangChain4j 0.36.2（`@AiService` + `@Tool`） |
| Embedding | BGE-small-en-v1.5 本地 ONNX 模型 |
| 向量存储 | Milvus（prod）/ InMemoryEmbeddingStore（dev） |
| 外部集成 | GitHub REST API v3（WebClient）、HMAC-SHA256 Webhook |
| 并发模型 | Virtual Threads + CompletableFuture |
| 流式输出 | SSE（TokenStream → Flux） |

---

## 可验证的真实数据

> 以下指标全部从代码库直接度量，可在面试中被技术面试官验证

### 工程规模

| 指标 | 数值 | 验证方式 |
|------|------|---------|
| 生产代码 | 20 个 Java 类，2034 行 | `find src -name "*.java" -path "*/main/*" \| xargs wc -l` |
| 测试代码 | 8 个测试类，689 行，15 个 @Test 方法 | `find src -name "*.java" -path "*/test/*"` |
| 测试代码占比 | 33.9%（689/2034） | 高于行业平均 20-25% |
| 自定义 LLM 适配层 | 601 行（3 个类） | `OpenAiProtocolSupport` 381 行为核心 |
| RAG 子系统 | 298 行（3 个类） | 含自定义 `MethodLevelDocumentSplitter` 95 行 |
| Agent 定义 | 275 行（5 个接口/类） | 4 个 `@AiService` + 1 个公共接口 |
| GitHub 集成 | 417 行（Controller + Adapter + Tool） | Webhook 120 行 + API 105 行 + Tool 192 行 |

### 架构指标

| 指标 | 数值 | 说明 |
|------|------|------|
| Agent 数量 | 4 个 `@AiService` Agent | Security / Style / Logic / Fix |
| 并行度 | 3 路 `CompletableFuture.supplyAsync` | Virtual Thread 执行，审查耗时 ≈ 最慢单 Agent |
| SSE 流合并 | `Flux.merge()` 合并 3 路 `TokenStream` | 真流式，非缓冲后拼接 |
| @Tool 方法 | 2 个（`fetchMrDiff` + `fetchCommitDiff`） | Agent 自主选择调用哪个 |
| 六角架构端口 | 2 个（`ReviewUseCase` 入站 + `GitHubPort` 出站） | 业务逻辑零外部依赖 |
| Embedding 维度 | 384 维（BGE-small-en-v1.5 ONNX） | 本地推理，无远程 API 调用 |
| 向量存储 | 双模式：InMemory（dev）/ Milvus（prod） | `@Profile` 切换 |

### 真实基准结果

| 场景 | 指标 | 数值 |
|------|------|------|
| small / sync | P50 | `40.899s` |
| small / stream | TTFB P50 | `6.414s` |
| small / stream | Total P50 | `93.747s` |
| small / orchestrated | P50 | `80.986s` |
| medium / sync | 单次 | `55.866s` |
| medium / stream | TTFB | `12.632s` |
| medium / stream | Total | `156.418s` |

### 关键技术实现量化

| 实现 | 具体内容 | 复杂度指标 |
|------|---------|-----------|
| SSE 解析器 | 手写逐行解析 + `[DONE]` 哨兵检测 | `PackyCodeStreamingChatModel` 125 行 |
| Tool Calling 协议 | delta 累积组装 `ToolExecutionRequest` | `OpenAiProtocolSupport.SseAccumulator` 内部类 |
| 方法级切分 | 正则匹配方法签名 + 花括号深度追踪 + 字符串转义处理 | 比通用 `DocumentSplitter` 多处理 3 种边界情况 |
| Webhook 签名 | HMAC-SHA256 + `HexFormat`（Java 17+） | 单点验证，拒绝重放攻击 |
| 消息序列化 | 支持 5 种消息类型（system/user/ai/tool_result/tool_spec） | `OpenAiProtocolSupport` 覆盖完整 OpenAI 协议 |

### 简历可引用句式（带真实数据）

- 自定义 LLM 适配层 **601 行**，手写 SSE 解析与 Tool Calling 协议，覆盖完整 OpenAI Chat Completions 协议
- 4 个 `@AiService` 声明式 Agent，3 路 Virtual Thread 并行执行，通过 `Flux.merge()` 实现真流式 SSE 输出
- RAG 子系统 **298 行**，自定义 `MethodLevelDocumentSplitter` 按 Java 方法签名切分，保证检索最小语义单元
- 测试覆盖率 **33.9%**（689 行测试 / 2034 行生产代码），含 **15** 个单元与集成测试用例
- GitHub 集成 **417 行**，支持 Webhook 自动触发（HMAC-SHA256 签名验证）+ PR Comment 回写 + Agent 自主 Diff 获取

---

## 中文简历写法

### 版本 A：适合一段项目描述

**Argus — 多 Agent 并行的自动化代码审查系统**（2025.12 – 2026.02）  
面向研发工作流构建的自动化代码审查系统，基于 Java 21 + LangChain4j 构建多 Agent 审查服务，利用 `@Tool` 实现 LLM 自主调用 GitHub API 获取代码变更，结合 RAG 检索团队内部规范增强审查上下文。采用 WebFlux + SSE 提供流式审查输出。

### 版本 B：适合 3-5 条项目要点（推荐 Java 后端岗）

- **AI 工程底座**：基于 Java 21 + LangChain4j 构建多 Agent 审查服务，利用 `@AiService` 声明式定义 SecurityAgent / StyleAgent / LogicAgent 三个审查 Agent 与 FixAgent，通过 `@Tool` 注解让 LLM 自主调用 GitHub API 获取 PR Diff、Commit Diff 和文件内容。
- **RAG 向量检索**：集成 Milvus 向量数据库落地 RAG 流水线，基于本地 ONNX BGE Embedding 模型实现离线向量化，自定义 `MethodLevelDocumentSplitter` 按 Java 方法签名切分规范文档，保证检索最小语义单元。
- **流式响应**：采用 WebFlux + SSE 异步非阻塞模型，将三路 Agent 的 `TokenStream` 合并为统一 `Flux` 流式输出；自定义 `PackyCodeChatModel` 手写 SSE 解析与 Tool Calling 协议适配，兼容非标 LLM API。
- **GitHub 集成**：实现 Webhook 自动触发审查（HMAC-SHA256 签名验证 + 异步派发），审查报告自动回写为 PR Comment；通过 `@Tool` 注解支持 LLM 自主选择获取 PR 或 Commit 级别的代码变更。
- **架构设计**：采用六角架构（Hexagonal Architecture），入站端口 `ReviewUseCase` 与出站端口 `GitHubPort` 解耦业务逻辑与外部依赖；三个 Agent 通过 Virtual Thread + `CompletableFuture` 并行执行，总审查时间 ≈ 最慢单 Agent 响应时间。

### 版本 C：适合校招 / 实习

- 独立搭建基于 **Java 21 + Spring Boot + LangChain4j** 的 AI 代码审查项目，实现三个专家 Agent 并行审查，并用 FixAgent 生成修复建议 patch。
- 在项目中实践了 RAG 向量检索、LLM Function Calling、SSE 流式输出、GitHub Webhook 集成等 AI 工程化能力。

### 版本 D：适合社招 / AI 工程方向

- 基于 LangChain4j `@AiService` + `@Tool` 构建多维度代码审查 Agent，自定义 ChatLanguageModel 手写 SSE 解析与 Tool Calling 协议，解决非标 LLM API 兼容问题。
- 落地 RAG 增强审查管线：本地 ONNX Embedding → Milvus 向量存储 → 方法级文档切分 → 上下文注入 Agent Prompt，审查意见从泛泛通用提升为团队规范导向。

---

## 简短简历表述

### 一句话版本

- 基于 **Java 21 + LangChain4j** 构建多 Agent 代码审查系统，通过 RAG 向量检索增强审查上下文，支持 GitHub Webhook 自动触发与 SSE 流式输出。
- 独立完成 **LangChain4j 多 Agent 审查服务**，自定义 LLM 适配层支持 Tool Calling，采用六角架构解耦外部依赖。

### 两到三条短 bullets

- 基于 **Java 21 + Spring Boot 3.5 + LangChain4j** 构建多 Agent 代码审查系统，三个专家 Agent 通过 Virtual Thread 并行执行；近期补充了 DeepSeek 实测，流式模式可将 small Diff 首字等待降到约 6.4s，但总耗时仍偏高。
- 落地 **RAG 审查管线**：本地 BGE Embedding + Milvus 向量存储 + 方法级文档切分。
- 实现 **GitHub Webhook 集成 + SSE 流式输出**，自定义 ChatModel 手写 SSE 解析与 Tool Calling 协议。

---

## 面试自我介绍版

### 中文

Argus 这个项目的核心是用 AI Agent 做自动化代码审查。我基于 Java 21 和 LangChain4j 搭了三个专家 Agent——分别负责安全漏洞、代码规范和业务逻辑审查。它们通过 Virtual Thread 并行跑，结果聚合成统一报告。

比较有挑战的部分有两块：一是 RAG，我用本地 ONNX Embedding 做向量化，写了一个按 Java 方法签名切分文档的 Splitter，保证检索粒度在方法级；二是 LLM 适配，因为用的模型接口不标准，我手写了完整的 SSE 解析和 Tool Calling 协议支持。

整个系统接了 GitHub Webhook，PR 提交后自动拉 Diff、审查、回写评论。架构上用了六角架构，把 Agent 层和 GitHub API 通过端口/适配器解耦。

---

## STAR 面试展开模板

### STAR 1：自定义 LLM 适配层

- **S**：LangChain4j 原生 OpenAI 适配器无法解析目标 LLM 的强制 SSE 输出和非标 Tool Calling 响应。
- **T**：需要自定义 ChatLanguageModel 实现，兼容 SSE 逐行解析、Tool Calling delta 累积和 reasoning 模型参数差异。
- **A**：手写 `PackyCodeChatModel` 和 `PackyCodeStreamingChatModel`，通过 Java HttpClient 直接调用 OpenAI 协议端点；提取 `OpenAiProtocolSupport` 处理消息序列化、SSE 事件累积和 ToolExecutionRequest 组装。
- **R**：三个 Agent 均可正常通过 Tool Calling 自主获取 GitHub PR/Commit Diff，流式输出可用。

### STAR 2：RAG 方法级文档切分

- **S**：通用文档切分器按固定字符数切分，导致 Java 方法被截断，向量检索返回不完整的代码片段。
- **T**：需要按语义单元切分——每个方法体作为独立检索单元。
- **A**：实现 `MethodLevelDocumentSplitter`，用正则匹配 Java 方法签名起始位置，再通过花括号深度追踪找到方法体结束，同时处理字符串内花括号转义。
- **R**：每个检索片段对应完整的单个方法，检索结果可直接作为 Agent 上下文使用，不需要二次裁剪。

### STAR 3：GitHub Webhook 全链路集成

- **S**：代码审查需要手动触发，无法融入开发流程。
- **T**：实现 PR 提交自动触发审查，报告自动回写为 PR Comment。
- **A**：实现 `GitHubWebhookController` 接收 pull_request 事件，HMAC-SHA256 验证签名；`GitHubReviewService` 异步拉取 Diff → 三 Agent 并行审查 → Markdown 格式化 → `GitHubApiAdapter` 通过 WebClient 回写评论；入口返回 202 Accepted 不阻塞。
- **R**：PR opened/synchronize/reopened 三种事件均可自动触发审查，审查报告以 Markdown 格式出现在 PR Comment 中。

---

## 不建议这样写

| 不要写 | 原因 |
|--------|------|
| 单次审查耗时 1 分钟以内 | 已被实测否定：small/orchestrated P50 约 80.986s，medium/stream 单次约 156.418s |
| TTFT 降至 300ms | 已被实测否定：small/stream TTFB P50 约 6.414s，medium/stream 单次约 12.632s |
| 吞吐能力提升约 3 倍 | 已被实测否定：当前吞吐约 0.01-0.02 req/s |
| RAG 命中率 90%+ | 只有 20 条小规模标注集 recall@3=1.0，不能外推生产命中率 |
| 自动修复系统 | 只有 FixAgent patch 生成，未做自动提交和真实仓库应用验证 |
| 大模型微服务 | 单体 Spring Boot 应用；已有容器化，但无服务拆分/服务发现 |
| 多 Agent 协同 | 实为并行聚合，无 Agent 间消息传递或依赖链 |
| 基于 OpenAI gpt-5.4 | 模型通过第三方中转站调用，应注明 |

## 建议用词替换

| 原描述 | 建议改为 |
|--------|---------|
| 多 Agent 协同 | 多 Agent 并行审查 |
| 修复系统 | 审查与修复建议系统 |
| 微服务 | 基于 Spring Boot 的审查服务 |
| TTFT 300ms | 删除 |

---

## 关键词

Java 21 / Spring Boot 3.5 / LangChain4j / @AiService / @Tool / RAG / Milvus / BGE Embedding / ONNX / WebFlux / SSE / Virtual Thread / GitHub Webhook / HMAC-SHA256 / 六角架构 / Function Calling
