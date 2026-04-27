# 整体架构设计

## 系统全景

```
                    GitHub Webhook (PR opened / CI failed / push)
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │        FastAPI Gateway         │
                    │   (Webhook 验证, 事件解析)       │
                    └───────────────┬───────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │     Orchestrator Agent         │
                    │                               │
                    │  1. 解析事件类型               │
                    │  2. 决定调度哪些 Agent          │
                    │  3. 并行/串行派发任务           │
                    │  4. 聚合结果                   │
                    │  5. 格式化输出到 GitHub         │
                    └──────┬───────────┬────────────┘
                           │           │
              ┌────────────┘           └────────────┐
              ▼                                     ▼
┌──────────────────────┐              ┌──────────────────────┐
│    Review Agent      │              │    CI/CD Agent       │
│                      │              │                      │
│  输入: PR Diff       │              │  输入: CI 日志        │
│  工具:               │              │  工具:               │
│  - get_file_content  │              │  - get_build_log     │
│  - search_codebase   │              │  - parse_error_trace │
│  - get_project_rules │              │  - search_solutions  │
│  输出: 审查意见列表    │              │  输出: 失败分析+修复  │
└──────────┬───────────┘              └──────────┬───────────┘
           │                                     │
           └──────────┬──────────────────────────┘
                      ▼
              ┌───────────────┐
              │   RAG 子系统   │
              │               │
              │  Qdrant 向量库 │
              │  BGE-M3 嵌入  │
              │               │
              │  索引内容:     │
              │  - 项目代码库  │
              │  - 历史 PR     │
              │  - 项目规范    │
              │  - 错误模式库  │
              └───────────────┘
```

## 核心设计原则

### 1. Agent 即函数

每个 Agent 本质上是一个接收结构化输入、返回结构化输出的异步函数。不搞复杂的 Agent 框架，不用 LangChain/CrewAI。

```python
# 伪代码 — Agent 的核心抽象
class Agent:
    def __init__(self, model: str, system_prompt: str, tools: list[Tool]):
        self.client = anthropic.Anthropic()  # 或 OpenAI
        self.system_prompt = system_prompt
        self.tools = tools

    async def run(self, input: AgentInput) -> AgentOutput:
        messages = [{"role": "user", "content": input.to_prompt()}]
        # tool calling loop
        while True:
            response = await self.client.messages.create(
                model=self.model,
                system=self.system_prompt,
                messages=messages,
                tools=self.tools,
            )
            if response.stop_reason == "tool_use":
                tool_result = await self.execute_tool(response.tool_calls)
                messages.append(response)
                messages.append(tool_result)
            else:
                return self.parse_output(response)
```

### 2. Orchestrator 是路由器，不是大脑

Orchestrator 不做复杂推理，它的逻辑是**确定性的规则路由**，不需要 LLM：

```python
# Orchestrator 的核心逻辑 — 纯规则，不用 LLM
class Orchestrator:
    async def handle_event(self, event: GitHubEvent) -> str:
        tasks = []

        if event.type == "pull_request":
            tasks.append(self.review_agent.run(event.diff))

        if event.type == "check_run" and event.status == "failure":
            tasks.append(self.cicd_agent.run(event.build_log))

        if event.type == "pull_request" and event.has_failed_checks:
            # PR 有代码改动 + CI 挂了 → 两个都派
            tasks.append(self.review_agent.run(event.diff))
            tasks.append(self.cicd_agent.run(event.build_log))

        results = await asyncio.gather(*tasks)
        return self.format_github_comment(results)
```

**为什么不用 LLM 做路由？** 因为事件类型是有限的、确定的。用 LLM 做路由是过度设计。Orchestrator 的价值在于**可靠调度**，不在于"智能"。

### 3. RAG 是共享基础设施

Review Agent 和 CI/CD Agent 都需要检索项目上下文，所以 RAG 子系统是独立的共享模块，不属于任何一个 Agent。

## 数据流

### 场景 1: PR 代码审查

```
1. GitHub 发送 pull_request.opened webhook
2. Gateway 验证签名，解析 payload
3. Orchestrator 识别为 PR 事件，调用 Review Agent
4. Review Agent:
   a. 调用 get_pr_diff 工具获取代码变更
   b. 调用 search_codebase 工具检索相关代码上下文 (RAG)
   c. 调用 get_project_rules 获取项目编码规范 (RAG)
   d. LLM 综合分析，生成审查意见
5. Orchestrator 将审查意见格式化为 Markdown
6. 通过 GitHub API 发送 PR Review Comment
```

### 场景 2: CI 构建失败

```
1. GitHub 发送 check_run.completed (conclusion=failure) webhook
2. Gateway 验证签名，解析 payload
3. Orchestrator 识别为 CI 失败事件，调用 CI/CD Agent
4. CI/CD Agent:
   a. 调用 get_build_log 工具获取完整构建日志
   b. 解析错误堆栈，提取关键错误信息
   c. 调用 search_solutions 在错误模式库中检索类似问题 (RAG)
   d. LLM 分析根因，生成修复建议
5. Orchestrator 格式化修复建议
6. 通过 GitHub API 发送 PR Comment
```

### 场景 3: PR + CI 同时触发

```
1. PR 打开后 CI 也失败了
2. Orchestrator 并行派发两个 Agent
3. asyncio.gather 等待两个结果
4. 合并为一条综合评论发到 PR
```

## 目录结构

```
argus/
├── src/
│   ├── __init__.py
│   ├── main.py                  # FastAPI 入口
│   ├── config.py                # 配置管理
│   ├── gateway/
│   │   ├── __init__.py
│   │   ├── webhook.py           # Webhook 路由 & 签名验证
│   │   └── events.py            # GitHub 事件模型
│   ├── agents/
│   │   ├── __init__.py
│   │   ├── base.py              # Agent 基类
│   │   ├── orchestrator.py      # Orchestrator (纯规则路由)
│   │   ├── review.py            # Review Agent
│   │   └── cicd.py              # CI/CD Agent
│   ├── tools/
│   │   ├── __init__.py
│   │   ├── github.py            # GitHub API 工具
│   │   ├── code_analysis.py     # 代码分析工具
│   │   └── log_parser.py        # 日志解析工具
│   ├── rag/
│   │   ├── __init__.py
│   │   ├── embedder.py          # Embedding 服务
│   │   ├── retriever.py         # 检索器 (Dense + BM25)
│   │   ├── reranker.py          # 重排序器
│   │   └── indexer.py           # 索引管理
│   └── github/
│       ├── __init__.py
│       ├── app.py               # GitHub App 认证
│       └── client.py            # GitHub API 客户端
├── tests/
│   ├── __init__.py
│   ├── test_review_agent.py
│   ├── test_cicd_agent.py
│   ├── test_orchestrator.py
│   └── fixtures/                # 测试用的 diff/log 样本
│       ├── sample_diffs/
│       └── sample_logs/
├── docs/
├── pyproject.toml
└── README.md
```
