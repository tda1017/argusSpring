# 整体架构设计

## 系统全景

```
                         两条入口，一套 Agent
                    ┌─────────────────────────────────────────┐
                    │                                         │
    路径 1: 终端交互  │                    路径 2: GitHub Webhook │
                    │                                         │
    用户终端          │         GitHub Webhook (PR / CI failed)  │
       │            │                        │                │
       ▼            │                        ▼                │
┌─────────────┐     │        ┌───────────────────────────────┐│
│    REPL     │     │        │        FastAPI Gateway         ││
│ prompt_toolkit    │        │   (Webhook 验证, 事件解析)       ││
└──────┬──────┘     │        └───────────────┬───────────────┘│
       │            │                        │                │
       ▼            │                        ▼                │
┌──────────────┐    │        ┌───────────────────────────────┐│
│ Conversation │    │        │     Orchestrator Agent         ││
│    Agent     │    │        │     (确定性规则路由)             ││
│  (LLM 意图   │    │        └──────┬───────────┬────────────┘│
│   理解)      │    │               │           │              │
└──┬───┬───┬──┘    │               │           │              │
   │   │   │       │               │           │              │
   │   │   │       │  ┌────────────┘           └──────────┐   │
   │   │   │       │  │                                   │   │
   ▼   ▼   ▼       │  ▼                                   ▼   │
┌──────────────────┴───────────────┐      ┌───────────────────┤
│        共享 Agent 层              │      │                   │
│                                  │      │                   │
│  ┌──────────┐ ┌──────────┐ ┌────┴─────┐│                   │
│  │ Review   │ │  CI/CD   │ │   Fix    ││                   │
│  │ Agent    │ │  Agent   │ │  Agent   ││                   │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘│                   │
│       │            │            │       │                   │
│       └────────┬───┴────────────┘       │                   │
│                ▼                        │                   │
│        ┌───────────────┐                │                   │
│        │   RAG 子系统   │                │                   │
│        │  Qdrant + BGE  │                │                   │
│        └───────────────┘                │                   │
└─────────────────────────────────────────┘
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

### 2. 两条入口，一套 Agent

系统有两条平行的入口路径，共享同一套底层 Agent：

| 入口 | 编排器 | 路由方式 | 适用场景 |
|------|--------|---------|---------|
| 用户终端 REPL | ConversationAgent | LLM 意图理解 | 开发者日常交互 |
| GitHub Webhook | Orchestrator | 确定性规则路由 | CI/CD 自动触发 |

**为什么不合并？** ConversationAgent 需要 LLM 理解自然语言意图，Orchestrator 处理结构化事件不需要 LLM。合并会让简单的 Webhook 路由背上 LLM 调用的延迟和成本。

### 3. Orchestrator 是路由器，不是大脑

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

### 4. RAG 是共享基础设施

Review Agent 和 CI/CD Agent 都需要检索项目上下文，所以 RAG 子系统是独立的共享模块，不属于任何一个 Agent。

## 数据流

### 场景 0: 交互式会话 (REPL)

```
1. 用户在终端运行 argus，启动 REPL
2. REPL 自动检测 git 上下文（分支、PR、CI 状态）
3. 用户输入自然语言或斜杠命令
4. ConversationAgent 理解意图，调用对应子 Agent
5. 子 Agent 执行分析，返回结构化结果
6. ConversationAgent 将结果流式输出到终端
7. 用户可继续对话（多轮），追问细节或请求修复
```

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

### 场景 4: 自动修复

```
1. Review Agent 或 CI/CD Agent 输出诊断结果
2. 用户在 REPL 中要求修复（"帮我修一下"）或 Orchestrator 配置了自动修复
3. ConversationAgent / Orchestrator 将诊断结果传给 Fix Agent
4. Fix Agent:
   a. 调用 get_file_content 获取需要修改的文件
   b. 调用 search_codebase 获取相关上下文
   c. LLM 基于诊断结果生成修复补丁
   d. 输出结构化的 patch（文件路径 + 变更内容）
5. 验证流水线（确定性，不依赖 LLM）:
   a. 语法校验 — 修改后的代码能 parse 吗
   b. 范围检查 — 改了多少行、多少文件，超限拒绝
   c. 接口守卫 — 公开 API 签名有没有被改动
   d. 静态分析 — ruff/mypy 有没有新增警告
   e. 测试门禁 — 在隔离 worktree 中跑测试
6. 根据验证结果分级处置:
   - auto_merge: 全部通过 + 高置信度 → 直接 apply / commit
   - suggest: blocking 通过 → 展示 diff 等人确认 / PR suggestion
   - comment_only: 低置信度 → 只描述问题，不提交代码
   - reject: 任何 blocking 失败 → 不执行
```

详见 [verification-pipeline.md](verification-pipeline.md)。

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
│   │   ├── conversation.py      # ConversationAgent (LLM 对话编排)
│   │   ├── review.py            # Review Agent
│   │   ├── cicd.py              # CI/CD Agent
│   │   └── fix.py               # Fix Agent (生成修复补丁)
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
│   ├── verification/
│   │   ├── __init__.py
│   │   ├── pipeline.py          # 验证流水线主逻辑
│   │   ├── stages.py            # 5 个验证阶段实现
│   │   ├── scope_policy.py      # 范围检查策略配置
│   │   ├── trust.py             # 信任分级逻辑
│   │   └── feedback.py          # 反馈闭环记录
│   ├── repl/
│   │   ├── __init__.py
│   │   ├── session.py           # 会话状态管理
│   │   ├── commands.py          # 斜杠命令处理
│   │   ├── renderer.py          # 输出渲染 (rich)
│   │   └── context.py           # git/PR/CI 上下文检测
│   └── github/
│       ├── __init__.py
│       ├── app.py               # GitHub App 认证
│       └── client.py            # GitHub API 客户端
├── tests/
│   ├── __init__.py
│   ├── test_review_agent.py
│   ├── test_cicd_agent.py
│   ├── test_fix_agent.py
│   ├── test_conversation_agent.py
│   ├── test_orchestrator.py
│   └── fixtures/                # 测试用的 diff/log 样本
│       ├── sample_diffs/
│       └── sample_logs/
├── docs/
├── pyproject.toml
└── README.md
```
