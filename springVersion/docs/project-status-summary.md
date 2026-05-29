# Argus Review — 项目现状总结

> 最后校验：2026-05-18  
> 项目路径：`argus/springVersion`  
> 定位：基于 Spring Boot 的多 Agent 智能代码审查系统

## 项目定位

面向研发工作流的自动化代码审查系统。接收代码变更（GitHub Webhook 或 REST API），通过 SecurityAgent / StyleAgent / LogicAgent 三个专家 Agent 并行审查，结合 RAG 检索团队内部规范，生成多维度审查报告。

**核心技术栈**：Java 21 + Spring Boot 3.5.3 + LangChain4j 0.36.2 + WebFlux + Milvus

## 当前真实实现状态

### ✅ 已完成（可编译运行、有测试覆盖）

| 模块 | 实现内容 | 关键文件 |
|------|---------|---------|
| 多 Agent 审查 | 3 个审查 Agent（Security/Style/Logic）并行执行，FixAgent 负责修复建议 | `domain/agent/*.java` |
| RAG 检索 | 本地 ONNX BGE Embedding + InMemory/Milvus 双模式存储 + 启动预加载 | `domain/rag/*.java` |
| 文档切分 | 方法级 DocumentSplitter（正则 + 花括号深度追踪） | `MethodLevelDocumentSplitter.java` |
| 自定义 LLM 适配 | PackyCodeChatModel + StreamingModel，手写 SSE 解析 + Tool Calling 协议 | `infrastructure/llm/*.java` |
| GitHub 集成 | Webhook 签名验证 + PR Diff 拉取 + 评论回写 + Commit Diff | `GitHubWebhookController.java`, `GitHubApiAdapter.java` |
| REST/SSE 接口 | 同步聚合 `/sync` + 流式 `/stream`（真 TokenStream → Flux） | `ReviewController.java` |
| @Tool 注册 | GitDiffTool 接入真实 GitHub API，三个 Agent 显式绑定 | `GitDiffTool.java` |
| 自动修复 | `FixAgent` 生成 unified diff patch，支持 `/api/v1/review/fix` | `FixAgent.java`, `FileContentTool.java`, `ReviewController.java` |
| 六角架构 | 入站端口 `ReviewUseCase` / 出站端口 `GitHubPort` / Application Service 编排 | `application/` 包 |
| 测试 | 单元测试 + LLM 集成测试（条件执行）+ 性能基准脚本 | 8 个测试类，689+ 行 |

### ⚠️ 限制与未完成

| 项目 | 现状 | 说明 |
|------|------|------|
| Agent "协同" | 链式协同初版 | 已实现 Security → Orchestrator → Fix 路由，但其他维度仍是并行聚合 |
| 自动修复 | 初版已实现 | 有 FixAgent、fileContentTool 和 `/fix` 端点，但缺少真实仓库 `git apply` 验证 |
| 性能基准 | 已有真实基准 | DeepSeek 小/中 Diff 已实测，结果证明原性能数字不能写 |
| RAG 命中率 | 小规模已验证 | 20 条标注数据 recall@3=1.0；`prod` profile + Milvus 复跑也为 1.0 |
| 部署 | 已容器化 | 有 Dockerfile、docker-compose、GitHub Actions；暂无 K8s |
| Milvus | 已接入并验证 | `prod` profile 下可运行，RAG 复跑 recall@3=1.0 |

## 技术栈明细

### 核心

- Java 21（Virtual Threads 已启用）
- Spring Boot 3.5.3
- Spring WebFlux（Reactor / SSE）
- LangChain4j 0.36.2（BOM 管理）

### AI/ML

- LangChain4j `@AiService` 声明式 Agent
- LangChain4j `@Tool` Function Calling
- BGE-small-en-v1.5 本地 ONNX Embedding（384 维）
- Milvus 向量数据库（prod profile）
- InMemoryEmbeddingStore（dev）

### 外部集成

- GitHub REST API v3（WebClient）
- HMAC-SHA256 Webhook 签名验证
- OpenAI Chat Completions 兼容协议（默认 DeepSeek 官方接口，可切换 packycode）

### 工程化

- Maven 单模块
- Lombok
- Spring Boot Actuator（health/info/metrics）
- 条件化 LLM 测试隔离

## 架构概览

```
interfaces/web          → ReviewController, GitHubWebhookController
application/service     → ReviewApplicationService, GitHubReviewService
application/port        → ReviewUseCase (in), GitHubPort (out)
domain/agent            → SecurityAgent, StyleAgent, LogicAgent
domain/rag              → RetrievalService, DocumentIngestionService, MethodLevelDocumentSplitter
domain/tool             → GitDiffTool
infrastructure/llm      → PackyCodeChatModel, PackyCodeStreamingChatModel, OpenAiProtocolSupport
infrastructure/config   → LangChain4jConfig
infrastructure/external → GitHubApiAdapter
```

## 代码规模

| 类别 | 文件数 | 总行数（约） |
|------|--------|-------------|
| 生产代码 | 20 | 2034 |
| 测试代码 | 8 | 689 |
| 测试占比 | — | 33.9% |
| 配置/构建 | 2 | ~210 |
| 脚本 | 2 | ~40 |

## 简历可用能力点（已落地）

1. **多 Agent 并行审查**：Security/Style/Logic 三个审查 Agent 并行执行，Virtual Thread + CompletableFuture
2. **FixAgent 修复建议**：基于审查结果生成 unified diff patch，提供 `/api/v1/review/fix` 端点
3. **RAG 增强审查**：本地 Embedding → 向量检索 → 上下文注入 → Agent Prompt
4. **自定义 LLM 适配**：手写 SSE 解析 + Tool Calling 协议支持，兼容非标 API
5. **GitHub Webhook 自动触发**：签名验证 → Diff 拉取 → 审查 → 评论回写
6. **流式输出**：TokenStream → Flux → SSE，三路流合并
7. **Agent 链式协同**：SecurityAgent 发现问题后由 Orchestrator 路由给 FixAgent 生成补丁
8. **六角架构**：端口/适配器模式，业务逻辑与外部依赖解耦

## 简历不可用描述（代码不支持）

- ~~单次审查耗时 1 分钟以内~~ — 实测 small/orchestrated P50 约 80.986s，medium/stream 单次约 156.418s
- ~~TTFT 300ms~~ — 实测 small/stream TTFB P50 约 6.414s，medium/stream 单次约 12.632s
- ~~吞吐提升 3 倍~~ — 实测吞吐约 0.01-0.02 req/s
- ~~生产 RAG 命中率 90%+~~ — 只有小规模 `docs/specs` 评估，不能代表生产
- ~~自动修复已生产验证~~ — 只有接口和 Agent，缺少真实仓库 patch 应用数据
- ~~微服务~~ — 单体应用
- ~~完整多 Agent 协同~~ — 只有 Security → Fix 链路，尚无全维度任务分解
