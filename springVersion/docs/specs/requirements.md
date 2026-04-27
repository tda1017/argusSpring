# Argus Review - Spring Boot 多 Agent 智能代码审查系统 - Requirements Document

基于 Spring Boot 3.5.3 + Java 21 + LangChain4j 的 RAG 增强多 Agent 代码审查系统。系统通过 GitHub Webhook 或 REST API 接收代码变更，利用 SecurityAgent、StyleAgent、LogicAgent 三个专家 Agent 并行审查，结合 RAG 检索内部规范上下文，生成多维度审查报告。采用六角架构（Hexagonal Architecture），支持同步聚合与 SSE 流式输出两种模式。

## Core Features

### F-01: 多 Agent 并行代码审查

系统核心能力。接收代码 Diff 后，同时派发三个专家 Agent 执行不同维度审查：

- **SecurityAgent** — 安全漏洞审查，对标 OWASP Top 10 / CWE，按 CRITICAL/HIGH/MEDIUM/LOW 分级输出
- **StyleAgent** — 代码规范审查，检查命名规范、代码结构、SOLID 原则、DDD 战术模式
- **LogicAgent** — 业务逻辑审查，关注业务正确性、并发安全、数据一致性、边界条件

三个 Agent 通过 Java 21 Virtual Threads 并行执行，结果聚合为统一审查报告。

### F-02: RAG 增强审查（内部规范检索）

审查前，RetrievalService 根据代码 Diff 向量检索最相关的内部规范片段（默认 top-3，相似度阈值 0.7），作为 Agent 审查的上下文注入。

- 向量化：本地 ONNX BGE-small-en-v1.5 Embedding 模型（离线可用）
- 向量存储：开发环境 InMemoryEmbeddingStore，生产环境 Milvus
- 文档切分：MethodLevelDocumentSplitter 按 Java 方法签名切分，保证最小语义单元

### F-03: GitHub Webhook 自动触发

监听 GitHub `pull_request` 事件（opened / synchronize / reopened），自动执行审查并将报告回写为 PR Comment。

- Webhook 签名验证（HMAC-SHA256）
- 异步执行，立即返回 202 Accepted
- 通过 GitHubPort 出站端口获取 PR Diff 并发表评论

### F-04: REST API 手动触发

提供两种 HTTP 接口供外部系统或开发者手动触发审查：

- `POST /api/v1/review/sync` — 同步模式，聚合三个 Agent 结果一次性返回 JSON
- `POST /api/v1/review/stream` — 流式模式，通过 SSE（text/event-stream）逐 Token 推送，带 `[SECURITY]`/`[STYLE]`/`[LOGIC]` 标签前缀

### F-05: 自定义 LLM 适配层

PackyCodeChatModel / PackyCodeStreamingChatModel 自定义实现 LangChain4j ChatLanguageModel 接口，兼容 packycode 强制 SSE 输出的 gpt-5.4 模型。通过 Java HttpClient 直接调用并逐行解析 SSE。

### F-06: LangChain4j AiService 声明式 Agent

三个审查 Agent 均通过 `@AiService` 注解声明，SystemMessage 定义专家 Persona，UserMessage 模板注入 codeDiff 和 context 变量。Spring Boot 自动装配。

## User Stories

- As a 开发者, I want PR 提交后自动收到多维度代码审查评论, so that 安全漏洞和规范问题在合并前被发现
- As a 团队 Lead, I want 审查结果按安全/规范/逻辑分类展示, so that 能快速定位最关键的问题
- As a DevOps 工程师, I want 通过 REST API 手动触发审查, so that 能集成到自定义 CI/CD 流水线
- As a 开发者, I want SSE 流式输出审查过程, so that 不用等待全部完成就能看到部分结果
- As a 架构师, I want 审查基于团队内部规范（RAG）, so that 审查意见与团队标准一致而非泛泛而谈

## Acceptance Criteria

- [ ] GitHub Webhook 接收 PR 事件后 < 5s 返回 202，后台异步完成审查
- [ ] 三个 Agent 并行执行，总审查时间 ≈ 最慢单 Agent 时间（非三者之和）
- [ ] Webhook 签名验证失败返回 401，不触发审查
- [ ] 同步接口返回包含 securityReport / styleReport / logicReport 三个字段的 JSON
- [ ] 流式接口返回 SSE 格式，每行带 Agent 标签前缀
- [ ] RAG 检索无匹配时返回 "暂无相关内部规范" 而非报错
- [ ] 本地 Embedding 模型无需外部 API Key 即可运行
- [ ] 审查报告自动回写为 PR Comment，格式为 Markdown

## Non-functional Requirements

### 性能
- 单次审查响应时间 < 60s（取决于 LLM 响应速度）
- Virtual Threads 并行，不阻塞平台线程
- WebFlux 非阻塞 I/O 处理 GitHub API 调用

### 安全
- API Key 通过环境变量注入，不硬编码
- Webhook Secret 通过环境变量配置
- HMAC-SHA256 签名验证防止伪造请求

### 可观测性
- Spring Boot Actuator 暴露 health / info / metrics 端点
- LangChain4j 请求/响应日志（DEBUG 级别）
- 关键流程 INFO 级别日志（Webhook 接收、审查开始/完成）

### 兼容性
- Java 21+（Virtual Threads 依赖）
- Spring Boot 3.5.3+
- LangChain4j 0.36.2（BOM 管理版本一致性）
- 兼容 OpenAI API 协议的 LLM 提供商
