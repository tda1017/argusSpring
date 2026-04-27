# Argus Review - Spring Boot 多 Agent 智能代码审查系统 - Design Document

## Overview

系统采用六角架构（Hexagonal Architecture），将业务逻辑与外部依赖解耦。核心审查流程由三个 LangChain4j AiService Agent 并行执行，通过 RAG 检索内部规范上下文增强审查质量。两条入口路径（GitHub Webhook + REST API）共享同一套 Agent 层。

```
┌─────────────────────────────────────────────────────────┐
│                    Interfaces Layer                      │
│  ┌──────────────────┐  ┌─────────────────────────────┐  │
│  │ ReviewController  │  │ GitHubWebhookController     │  │
│  │ (REST/SSE)       │  │ (Webhook 签名验证 + 事件解析) │  │
│  └────────┬─────────┘  └──────────────┬──────────────┘  │
│           │                           │                  │
├───────────┼───────────────────────────┼──────────────────┤
│           ▼          Application      ▼                  │
│  ┌─────────────────┐  ┌──────────────────────────────┐  │
│  │ ReviewUseCase   │  │ GitHubReviewService          │  │
│  │ (入站端口)       │  │ (PR 审查编排)                 │  │
│  └────────┬────────┘  └──────────────┬───────────────┘  │
│           │                          │                   │
│           ▼                          ▼                   │
│  ┌──────────────────────────────────────────────────┐   │
│  │         ReviewApplicationService                  │   │
│  │  ┌────────────┐ ┌──────────┐ ┌────────────────┐  │   │
│  │  │RAG Context │→│ 3 Agents │→│ Result Merge   │  │   │
│  │  └────────────┘ │(parallel)│ └────────────────┘  │   │
│  │                 └──────────┘                      │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
├──────────────────────────────────────────────────────────┤
│                     Domain Layer                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                │
│  │Security  │ │Style     │ │Logic     │  CodeReviewAgent│
│  │Agent     │ │Agent     │ │Agent     │  (顶层接口)      │
│  └──────────┘ └──────────┘ └──────────┘                │
│  ┌──────────────────────────────────────┐               │
│  │ RAG: RetrievalService               │               │
│  │      MethodLevelDocumentSplitter    │               │
│  └──────────────────────────────────────┘               │
│  ┌──────────────────────────────────────┐               │
│  │ Tool: GitDiffTool (Function Calling) │               │
│  └──────────────────────────────────────┘               │
│                                                          │
├──────────────────────────────────────────────────────────┤
│                  Infrastructure Layer                    │
│  ┌──────────────────┐  ┌─────────────────────────────┐  │
│  │ LangChain4jConfig│  │ GitHubApiAdapter            │  │
│  │ PackyCodeChat    │  │ (WebClient → GitHub API)    │  │
│  │ Model (SSE解析)  │  │ 实现 GitHubPort 出站端口    │  │
│  └──────────────────┘  └─────────────────────────────┘  │
│  ┌──────────────────────────────────────┐               │
│  │ EmbeddingStore (InMemory / Milvus)  │               │
│  │ EmbeddingModel (BGE ONNX 本地)       │               │
│  └──────────────────────────────────────┘               │
└──────────────────────────────────────────────────────────┘
```

## Architecture

### 分层结构

| 层 | 包路径 | 职责 |
|---|--------|------|
| interfaces | `com.argus.review.interfaces.web` | HTTP 入口，请求/响应转换 |
| application | `com.argus.review.application` | 用例编排，端口定义 |
| domain | `com.argus.review.domain` | Agent 定义，RAG 检索，Tool 定义 |
| infrastructure | `com.argus.review.infrastructure` | LLM 适配，GitHub API 客户端，向量存储 |

### 端口与适配器

**入站端口（Driving）：**
- `ReviewUseCase` — 代码审查用例接口，定义 `review()` 和 `reviewStream()`

**出站端口（Driven）：**
- `GitHubPort` — GitHub 平台操作抽象，`fetchPrDiff()` / `postPrComment()`

**适配器：**
- `GitHubApiAdapter` — 基于 WebClient 实现 GitHubPort
- `PackyCodeChatModel` — 自定义 ChatLanguageModel，兼容强制 SSE 输出

## Components and Interfaces

### 1. ReviewController（接口层）

```
POST /api/v1/review/sync   → ReviewResult (JSON)
POST /api/v1/review/stream  → Flux<String> (SSE)
```

请求体：`ReviewRequest(projectId, mrId, codeDiff)`

### 2. GitHubWebhookController（接口层）

```
POST /api/v1/github/webhook
  Headers: X-GitHub-Event, X-Hub-Signature-256
  Body: raw JSON payload
```

处理流程：签名验证 → 事件过滤（仅 pull_request） → action 过滤（opened/synchronize/reopened） → 异步派发审查 → 返回 202

### 3. ReviewApplicationService（应用层）

核心编排逻辑：

```java
// 同步模式
review(codeDiff):
  1. retrievalService.retrieveRelevantContext(codeDiff)  // RAG
  2. 三个 Agent 通过 VirtualThread CompletableFuture 并行
  3. allOf().join() 等待全部完成
  4. 聚合为 ReviewResult

// 流式模式
reviewStream(codeDiff):
  1. RAG 检索上下文
  2. 三个 Agent 各自 subscribeOn(boundedElastic)
  3. Flux.merge() 合并三路流，带标签前缀
```

### 4. GitHubReviewService（应用层）

PR 审查编排：fetchPrDiff → review → 格式化 Markdown → postPrComment

### 5. Agent 层（领域层）

```java
interface CodeReviewAgent {
    String review(String codeDiff, String context);
}

// 三个实现均为 @AiService 声明式接口
SecurityAgent extends CodeReviewAgent  // OWASP Top 10
StyleAgent extends CodeReviewAgent     // 命名/结构/SOLID
LogicAgent extends CodeReviewAgent     // 业务正确性/并发
```

每个 Agent 通过 `@SystemMessage` 定义专家 Persona，`@UserMessage` 模板注入变量。

### 6. RetrievalService（领域层 - RAG）

```java
retrieveRelevantContext(query, maxResults=3, minScore=0.7):
  1. embeddingModel.embed(query) → 向量
  2. embeddingStore.findRelevant(vector, maxResults, minScore)
  3. 拼接匹配片段，附带相似度分数
```

### 7. PackyCodeChatModel（基础设施层）

自定义 LLM 客户端，解决 packycode gpt-5.4 强制 SSE 输出问题：
- `buildRequestBody()` — 构建 OpenAI 兼容请求，强制 `stream=true`
- `parseSseResponse()` — 逐行解析 `data:` 前缀 SSE 事件，拼接 content delta
- reasoning 模型跳过 temperature 参数

## Data Models

### ReviewRequest（入站）
```java
record ReviewRequest(String projectId, String mrId, String codeDiff)
```

### ReviewResult（出站）
```java
record ReviewResult(String securityReport, String styleReport, String logicReport)
```

### GitHubPort 接口
```java
Mono<String> fetchPrDiff(owner, repo, prNumber)
Mono<Long> postPrComment(owner, repo, prNumber, body)
```

### 向量存储
- 开发环境：`InMemoryEmbeddingStore<TextSegment>`
- 生产环境：Milvus（TODO，通过 `@Profile("prod")` 切换）

## Error Handling

| 场景 | 策略 |
|------|------|
| Webhook 签名验证失败 | 返回 401 Unauthorized，不触发审查 |
| GitHub API 调用失败 | Mono.error 传播，日志记录，subscribe 中 onError 处理 |
| LLM 调用超时/失败 | RuntimeException 包装，CompletableFuture 异常传播 |
| SSE 流式异常 | onErrorResume 返回 `[ERROR]` 标记消息 |
| RAG 无匹配结果 | 返回 "暂无相关内部规范" 文本，不中断审查 |
| SSE 解析单行失败 | warn 日志，跳过该 chunk 继续解析 |
| Webhook payload 解析失败 | 返回 400 Bad Request |

## Testing Strategy

### 单元测试
- Agent 接口 Mock 测试（验证 prompt 模板变量注入）
- MethodLevelDocumentSplitter 切分逻辑测试
- Webhook 签名验证逻辑测试

### 集成测试
- LLM 链路验证（`LlmLinkVerificationTest` — 通过 `verify-llm.sh` 脚本注入真实 API Key）
- PackyCodeChatModel SSE 解析测试
- PackyCodeStreamingChatModel 流式输出测试

### 端到端测试
- Webhook → 审查 → PR Comment 全链路（需 GitHub API Token）
- REST API 同步/流式接口响应格式验证

### 测试环境
- `@Profile("!prod")` 使用 InMemoryEmbeddingStore
- 本地 ONNX Embedding 模型，无需外部依赖
