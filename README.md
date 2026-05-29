# Argus — 多 Agent 并行的自动化代码审查系统

> 希腊神话中百眼巨人，全方位审视你的代码。

## 项目定位

面向研发工作流的自动化代码审查系统。接收代码变更（GitHub Webhook 或 REST API），通过多个专家 Agent 并行审查，结合 RAG 检索团队内部规范，生成多维度审查报告与修复建议。

## 两个版本

| 版本 | 路径 | 技术栈 | 状态 |
|------|------|--------|------|
| **Spring Boot（主力）** | [`springVersion/`](springVersion/) | Java 21 + Spring Boot 3.5 + LangChain4j + WebFlux | 活跃开发 |
| Python（早期原型） | [`argus/`](argus/) | Python 3.11 + FastAPI + Anthropic/OpenAI SDK | 功能完整，不再迭代 |

**当前重点在 Spring Boot 版本。** Python 版为早期原型，功能验证完毕后作为参考保留。

## Spring Boot 版核心能力

| 能力 | 实现 |
|------|------|
| 多 Agent 并行审查 | Security/Style/Logic 三路 VirtualThread 并行 |
| RAG 增强审查 | 本地 BGE Embedding + 向量检索 + 方法级文档切分 |
| FixAgent 修复建议 | unified diff patch 生成 |
| Agent 链式路由 | Security → Orchestrator → Fix |
| GitHub Webhook | HMAC-SHA256 签名验证 → 自动审查 → PR Comment |
| SSE 流式输出 | TokenStream → Flux 三路合并 |
| 自定义 LLM 适配 | 手写 SSE 解析 + Tool Calling 协议（601 行） |

## 快速开始

```bash
# Spring Boot 版
cd springVersion
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run

# Docker 版仍建议用环境变量，不把本地密钥打进镜像
DEEPSEEK_API_KEY=sk-... docker compose up --build
```

```bash
# Python 版
cd argus && uv sync --extra dev
uv run argus  # REPL 模式
```

## 文档入口

| 文档 | 说明 |
|------|------|
| [springVersion/docs/README.md](springVersion/docs/README.md) | **Spring 版文档中心**（主力） |
| [docs/README.md](docs/README.md) | Python 版设计文档（Legacy，部分理念有参考价值） |

## API 端点（Spring 版）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/review/sync` | 同步多 Agent 审查 |
| POST | `/api/v1/review/stream` | SSE 流式审查 |
| POST | `/api/v1/review/fix` | 生成修复 patch |
| POST | `/api/v1/review/orchestrated` | Security → Fix 链式审查 |
| POST | `/api/v1/github/webhook` | GitHub Webhook 接收 |
