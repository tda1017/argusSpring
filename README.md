# Argus — Multi-Agent Code Review & CI/CD System

> 希腊神话中的百眼巨人，全方位审视你的代码。

## 项目定位

一个基于多 Agent 协作的智能 DevOps 系统，能自动审查 PR 代码质量并分析 CI/CD 构建失败，通过 GitHub App 集成到真实开发流程中。

## 核心能力

| Agent | 职责 | 关键技术 |
|-------|------|----------|
| **Orchestrator** | 接收 Webhook、路由分发、结果聚合 | Event routing, Agent 编排 |
| **Review Agent** | 代码 Diff 分析、模式检测、规范检查 | RAG, Function Calling, AST 分析 |
| **CI/CD Agent** | 构建日志解析、失败定位、修复建议 | 日志 chunking, 错误模式匹配 |

## 技术栈

- **语言**: Python 3.11+
- **框架**: FastAPI
- **LLM**: Claude / OpenAI (通过 SDK 直接调用，不使用 LangChain)
- **向量库**: Qdrant
- **Embedding**: BGE-M3
- **集成**: GitHub App + Webhook

## 文档索引

| 文档 | 说明 |
|------|------|
| [architecture.md](docs/architecture.md) | 整体架构设计 |
| [orchestrator-agent.md](docs/orchestrator-agent.md) | Orchestrator 详细设计 |
| [review-agent.md](docs/review-agent.md) | Review Agent 详细设计 |
| [cicd-agent.md](docs/cicd-agent.md) | CI/CD Agent 详细设计 |
| [rag-pipeline.md](docs/rag-pipeline.md) | RAG 子系统设计 |
| [tools-spec.md](docs/tools-spec.md) | 工具接口定义 |
| [metrics.md](docs/metrics.md) | 量化指标与评测方案 |
| [development-guide.md](docs/development-guide.md) | 开发指引 (给实现 agent 看的) |

## 开发顺序

```
Phase 1: Review Agent (单 Agent，能独立工作)
Phase 2: CI/CD Agent (第二个独立 Agent)
Phase 3: Orchestrator (串联两个 Agent，形成完整系统)
Phase 4: GitHub App 集成 + 评测
```
