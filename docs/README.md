# docs/ — Python 版设计文档（Legacy）

> 本目录为早期 Python 版本（`argus/`）的设计文档。  
> **当前主力版本为 Spring Boot（`springVersion/`）**，其文档入口见 [`springVersion/docs/README.md`](../springVersion/docs/README.md)。

## 仍有参考价值

| 文档 | 用途 |
|------|------|
| [architecture.md](architecture.md) | 多入口 + 共享 Agent 层的总体思路，Spring 版沿用了此架构理念 |
| [orchestrator-agent.md](orchestrator-agent.md) | Orchestrator 确定性路由设计 |
| [review-agent.md](review-agent.md) | Review Agent 设计（Spring 版拆为 Security/Style/Logic） |
| [fix-agent.md](fix-agent.md) | Fix Agent 设计思路 |
| [rag-pipeline.md](rag-pipeline.md) | RAG 子系统理念（Spring 版简化落地） |
| [tools-spec.md](tools-spec.md) | Tool Calling 接口约定 |

## 仅 Python 版可用

| 文档 | 说明 |
|------|------|
| [conversation-agent.md](conversation-agent.md) | ConversationAgent（Spring 版无此模块） |
| [cicd-agent.md](cicd-agent.md) | CI/CD Agent（Spring 版无此模块） |
| [cli-and-config.md](cli-and-config.md) | REPL / CLI 交互模式 |
| [user-experience.md](user-experience.md) | 增量审查、噪音控制 |
| [provider-config.md](provider-config.md) | LLM Provider 配置 |
| [development-guide.md](development-guide.md) | Python 版开发指引 |

## 已删除

- ~~verification-pipeline.md~~ — 两个版本均未实现，删除
- ~~metrics.md~~ — 无落地代码，删除
