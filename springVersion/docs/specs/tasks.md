# Argus Review - Spring Boot 多 Agent 智能代码审查系统 - Task List

## Implementation Tasks

- [x] 1. **基础设施层 — LLM 适配与配置**
    - [x] 1.1. PackyCodeChatModel 自定义 ChatLanguageModel 实现
        - *Goal*: 兼容 packycode 强制 SSE 输出的 gpt-5.4 模型
        - *Details*: Java HttpClient 直接调用 /v1/chat/completions，逐行解析 SSE data: 前缀事件，拼接 content delta；reasoning 模型跳过 temperature 参数；强制 stream=true
        - *Requirements*: F-05
    - [x] 1.2. PackyCodeStreamingChatModel 流式实现
        - *Goal*: 支持 LangChain4j StreamingChatLanguageModel 接口的真流式输出
        - *Details*: 基于 HttpClient 异步 BodyHandler 逐 chunk 推送 StreamingResponseHandler
        - *Requirements*: F-05
    - [x] 1.3. LangChain4jConfig 统一配置
        - *Goal*: Spring Bean 装配 ChatModel、StreamingChatModel、EmbeddingModel、EmbeddingStore
        - *Details*: 从 application.yml 读取配置；本地 ONNX BGE Embedding；InMemoryEmbeddingStore（dev）/ Milvus（prod，TODO）
        - *Requirements*: F-05, F-02

- [x] 2. **领域层 — Agent 定义与 RAG**
    - [x] 2.1. CodeReviewAgent 顶层接口
        - *Goal*: 定义多 Agent 协同的统一抽象
        - *Details*: `review(codeDiff, context)` 方法签名，使用 LangChain4j @V 注解
        - *Requirements*: F-01, F-06
    - [x] 2.2. SecurityAgent / StyleAgent / LogicAgent 三个 @AiService 实现
        - *Goal*: 三个专家维度的审查 Agent
        - *Details*: 各自 @SystemMessage 定义 Persona（安全/规范/逻辑），@UserMessage 模板注入 codeDiff 和 context；Spring Boot 自动装配
        - *Requirements*: F-01, F-06
    - [x] 2.3. RetrievalService RAG 检索服务
        - *Goal*: 审查前检索最相关的内部规范片段
        - *Details*: 向量化查询 → embeddingStore.findRelevant(top-3, minScore=0.7) → 拼接规范片段；无匹配返回 "暂无相关内部规范"
        - *Requirements*: F-02
    - [x] 2.4. MethodLevelDocumentSplitter 方法级切分器
        - *Goal*: 按 Java 方法签名切分代码文档，保证最小语义单元
        - *Details*: 正则匹配方法签名 → 花括号深度追踪 → 字符串转义处理；前导内容（import/类声明）独立片段
        - *Requirements*: F-02
    - [x] 2.5. GitDiffTool Function Calling 工具（模拟实现）
        - *Goal*: LLM 可自主调用获取 MR/Commit Diff
        - *Details*: @Tool 注解声明；当前为模拟实现，TODO 接入真实 API
        - *Requirements*: F-01

- [x] 3. **应用层 — 用例编排**
    - [x] 3.1. ReviewUseCase 入站端口定义
        - *Goal*: 定义代码审查用例接口
        - *Details*: `review(codeDiff)` 同步聚合 → ReviewResult；`reviewStream(codeDiff)` 流式 → Flux<String>
        - *Requirements*: F-01, F-04
    - [x] 3.2. ReviewApplicationService 多 Agent 编排
        - *Goal*: 编排 RAG 检索 + 三 Agent 并行执行 + 结果聚合
        - *Details*: 同步模式用 VirtualThread CompletableFuture 并行；流式模式用 Flux.merge 合并三路流，带 [SECURITY]/[STYLE]/[LOGIC] 标签
        - *Requirements*: F-01, F-04
    - [x] 3.3. GitHubPort 出站端口定义
        - *Goal*: 抽象 GitHub 平台操作
        - *Details*: `fetchPrDiff()` / `postPrComment()` 返回 Mono
        - *Requirements*: F-03
    - [x] 3.4. GitHubReviewService PR 审查编排
        - *Goal*: 串联 fetchDiff → review → 格式化 Markdown → postComment
        - *Details*: 异步 Mono 链式调用；审查报告格式化为 Markdown（安全/规范/逻辑三段）
        - *Requirements*: F-03

- [x] 4. **接口层 — HTTP 入口**
    - [x] 4.1. ReviewController REST/SSE 接口
        - *Goal*: 提供同步和流式两种审查入口
        - *Details*: POST /api/v1/review/sync (JSON) + POST /api/v1/review/stream (SSE)；请求体 ReviewRequest(projectId, mrId, codeDiff)
        - *Requirements*: F-04
    - [x] 4.2. GitHubWebhookController Webhook 接收
        - *Goal*: 接收 GitHub PR 事件并触发自动审查
        - *Details*: HMAC-SHA256 签名验证；仅处理 pull_request 事件的 opened/synchronize/reopened action；异步派发返回 202
        - *Requirements*: F-03

- [x] 5. **基础设施层 — GitHub API 适配**
    - [x] 5.1. GitHubApiAdapter WebClient 实现
        - *Goal*: 实现 GitHubPort 出站端口
        - *Details*: WebClient 调用 GitHub REST API v3；fetchPrDiff 使用 diff Accept header；postPrComment 通过 issues API 发表评论
        - *Requirements*: F-03

- [x] 6. **配置与运维**
    - [x] 6.1. application.yml 配置
        - *Goal*: 统一管理所有外部配置
        - *Details*: LangChain4j LLM 配置、GitHub API 配置、Actuator 端点、Virtual Threads 启用、日志级别
        - *Requirements*: F-05, F-03
    - [x] 6.2. pom.xml 依赖管理
        - *Goal*: Maven 依赖声明与版本管理
        - *Details*: Spring Boot 3.5.3 parent、LangChain4j 0.36.2 BOM、WebFlux/Validation/Actuator/Lombok/Test
        - *Requirements*: 全部

- [x] 7. **测试**
    - [x] 7.1. MethodLevelDocumentSplitter 单元测试
        - *Goal*: 验证方法级切分逻辑正确性
        - *Details*: 多方法类、嵌套花括号、字符串内花括号等边界场景
        - *Requirements*: F-02
    - [x] 7.2. LLM 链路集成测试
        - *Goal*: 验证 PackyCodeChatModel 能正确调用 LLM 并解析响应
        - *Details*: LlmLinkVerificationTest + verify-llm.sh 脚本注入 API Key
        - *Requirements*: F-05
    - [x] 7.3. SSE 流式输出测试
        - *Goal*: 验证 PackyCodeStreamingChatModel 流式解析
        - *Details*: Gpt54StreamingTest 端到端流式验证
        - *Requirements*: F-05

---

## TODO — 待改进项

- [x] 8. **reviewStream() 真流式改造**
    - *Priority*: HIGH
    - *Goal*: 当前 `reviewStream()` 是伪流式（同步调用后按行切分模拟 token 流），需接入 `PackyCodeStreamingChatModel` 实现真 SSE 流式
    - *Details*: 需改造 Agent 接口签名（返回 `TokenStream` 或 `Flux<String>`），修改 `ReviewApplicationService` 编排逻辑，合并三路真实 SSE 流
    - *Affected files*: `CodeReviewAgent.java`, `SecurityAgent.java`, `StyleAgent.java`, `LogicAgent.java`, `ReviewApplicationService.java`
    - *Requirements*: F-04

- [x] 9. **Milvus 向量存储生产适配**
    - *Priority*: MEDIUM
    - *Goal*: 当前仅 InMemoryEmbeddingStore（dev），生产环境需接入 Milvus
    - *Details*: 实现 `@Profile("prod")` 的 MilvusEmbeddingStore Bean；配置 host/port/collection/dimension
    - *Affected files*: `LangChain4jConfig.java`, `application.yml`
    - *Requirements*: F-02

- [x] 10. **GitDiffTool 接入真实 API**
    - *Priority*: MEDIUM
    - *Goal*: 当前 `GitDiffTool` 为模拟实现，需接入 GitHub/GitLab 真实 API
    - *Details*: 注册为 Spring Bean，注入 WebClient 或 GitHubPort；替换硬编码模拟 Diff
    - *Affected files*: `GitDiffTool.java`
    - *Requirements*: F-01

- [x] 11. **RAG 文档预加载机制**
    - *Priority*: MEDIUM
    - *Goal*: 当前 InMemoryEmbeddingStore 启动时为空，无规范文档可检索
    - *Details*: 需实现启动时从指定目录/Git 仓库加载规范文档 → MethodLevelDocumentSplitter 切分 → 向量化 → 存入 EmbeddingStore
    - *Affected files*: 新增 `DocumentIngestionService.java` 或在 `LangChain4jConfig` 中添加 `@PostConstruct` 加载逻辑
    - *Requirements*: F-02

- [x] 12. **请求参数校验**
    - *Priority*: LOW
    - *Goal*: ReviewController 的 ReviewRequest 缺少 `@Valid` 校验
    - *Details*: codeDiff 不能为空/null；projectId、mrId 可选但 codeDiff 必填；添加 `@NotBlank` 注解 + `@Valid`
    - *Affected files*: `ReviewController.java`
    - *Requirements*: F-04

- [x] 13. **VirtualThread Executor 共享优化** ✅ 已修复
    - ~~*Details*: `ReviewApplicationService.review()` 每次创建 3 个 Executor，已合并为 1 个共享实例~~

- [ ] 14. **application.yml embedding-model 配置清理** ✅ 已修复
    - ~~*Details*: 移除多余远程 embedding-model 配置，避免与本地 ONNX Bean 冲突~~

## Task Dependencies

- Task 1（基础设施-LLM）必须先于 Task 2（领域层），Agent 依赖 ChatModel Bean
- Task 2（领域层）必须先于 Task 3（应用层），编排依赖 Agent 和 RAG
- Task 3（应用层）必须先于 Task 4（接口层），Controller 依赖 UseCase
- Task 5（GitHub 适配）与 Task 2 可并行，仅需 Task 3.3 端口定义
- Task 6（配置）贯穿全程，随各层开发同步推进
- Task 7（测试）在对应功能完成后执行

```
Task 1 (LLM 适配) ──→ Task 2 (Agent/RAG) ──→ Task 3 (编排) ──→ Task 4 (HTTP 入口)
                                                    ↑
Task 5 (GitHub 适配) ───────────────────────────────┘
Task 6 (配置) ── 贯穿全程
Task 7 (测试) ── 各层完成后
```

## Current Status

- **MVP 已实现**: Task 1-7 全部完成，编译通过，启动成功（4.5s），单元测试全绿
- **已修复**: Task 8-14 已完成，其中 Task 13（Executor 共享）、Task 14（yml 配置清理）已落地
- **当前状态**: `tasks.md` 中列出的开发任务已全部完成
