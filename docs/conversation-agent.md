# Conversation Agent 详细设计

## 定位

ConversationAgent 是 Argus 的**交互式入口 Agent**。用户在终端输入 `argus` 启动 REPL 后，ConversationAgent 负责：

1. 理解用户的自然语言意图
2. 调用合适的子 Agent（Review Agent / CI/CD Agent）执行具体任务
3. 维护多轮对话上下文
4. 将结果以流式方式展示给用户

它是一个 **LLM 驱动的对话编排器**，与 Orchestrator 的确定性规则路由形成互补。

## 与 Orchestrator 的区别

| 维度 | Orchestrator | ConversationAgent |
|------|-------------|-------------------|
| 入口 | GitHub Webhook | 用户终端 REPL |
| 输入 | 结构化事件 (GitHubEvent) | 自然语言 + 斜杠命令 |
| 路由方式 | 确定性 if/else 规则 | LLM 意图理解 |
| 是否需要 LLM | 否 | 是 |
| 输出目标 | GitHub PR Comment | 终端流式输出 |
| 会话状态 | 无状态（每个 Webhook 独立） | 有状态（多轮对话） |

**两者是平行的入口编排器**，共享底层的 Review Agent 和 CI/CD Agent。不合并的原因：职责完全不同，合并违反单一职责原则。

## System Prompt

```text
你是 Argus，一个交互式代码审查与 CI/CD 诊断助手。

你运行在一个 git 仓库的上下文中，可以：
- 审查代码变更（调用 review 工具）
- 分析 CI 构建失败（调用 diagnose 工具）
- 获取当前 git 状态、PR 信息、CI 状态

行为准则：
1. 优先使用上下文信息（当前分支、已检测到的 PR）减少用户输入
2. 输出简洁、可操作的建议，不废话
3. 如果用户请求不明确，主动询问而不是猜测
4. 对于大型 diff，先给摘要，用户要求时再展开细节
```

## 工具定义

ConversationAgent 通过 Function Calling 调用以下工具：

### `review`

调用 Review Agent 执行代码审查。

```python
name: "review"
description: "审查代码变更，分析代码质量问题并给出改进建议"
parameters:
  diff: string           # unified diff 内容
  rules: string | null   # 项目规范 (来自 argus.md)
returns: ReviewOutput    # 结构化审查结果
```

### `diagnose`

调用 CI/CD Agent 分析构建失败。

```python
name: "diagnose"
description: "分析 CI/CD 构建日志，定位失败根因并给出修复建议"
parameters:
  log: string            # 构建日志内容
returns: CICDOutput      # 结构化诊断结果
```

### `fix`

调用 Fix Agent 生成修复补丁。

```python
name: "fix"
description: "基于审查或诊断结果生成修复补丁"
parameters:
  diagnosis: object      # ReviewOutput 或 CICDOutput
  file_paths: list[string] | null  # 需要修复的文件路径（可选）
returns: FixOutput       # 修复补丁列表
```

### `get_git_context`

获取当前 git 仓库的上下文信息。

```python
name: "get_git_context"
description: "获取当前 git 仓库状态：分支、暂存区变更、最近提交"
parameters: {}
returns:
  repo_root: string
  branch: string
  staged_diff: string | null    # git diff --staged
  recent_commits: list[string]  # 最近 5 条 commit message
  has_uncommitted: bool
```

实现：纯 git 命令，不需要 LLM。

```python
async def get_git_context() -> dict:
    repo_root = run("git rev-parse --show-toplevel")
    branch = run("git rev-parse --abbrev-ref HEAD")
    staged_diff = run("git diff --staged")
    recent = run("git log --oneline -5")
    status = run("git status --porcelain")
    return {
        "repo_root": repo_root,
        "branch": branch,
        "staged_diff": staged_diff or None,
        "recent_commits": recent.splitlines(),
        "has_uncommitted": bool(status.strip()),
    }
```

### `get_pr_info`

获取当前分支关联的 PR 信息。

```python
name: "get_pr_info"
description: "获取当前分支对应的 Pull Request 信息（标题、描述、diff）"
parameters:
  repo: string | null     # 不填则从 git remote 推断
  pr_number: int | null   # 不填则根据当前分支查找
returns:
  pr_number: int
  title: string
  body: string
  diff: string
  state: string           # "open" | "closed" | "merged"
  checks_status: string   # "success" | "failure" | "pending"
```

### `get_ci_status`

获取当前分支或 PR 的 CI 构建状态。

```python
name: "get_ci_status"
description: "获取 CI/CD 构建状态，如果有失败则返回日志摘要"
parameters:
  repo: string | null
  pr_number: int | null
returns:
  status: string            # "success" | "failure" | "pending" | "none"
  failed_jobs: list[{name: string, log_excerpt: string}] | null
  run_url: string | null
```

## Session 数据结构

```python
@dataclass
class Session:
    """REPL 会话状态"""
    messages: list[dict]           # 对话历史 (role + content)
    repo_root: str | None          # git 仓库根目录
    git_branch: str | None         # 当前分支
    pr_number: int | None          # 检测到的 PR 编号
    cached_diff: str | None        # 缓存的 diff（避免重复获取）
    cached_review: ReviewOutput | None   # 缓存的审查结果
    cached_ci_log: str | None      # 缓存的 CI 日志

    def add_message(self, role: str, content: str):
        self.messages.append({"role": role, "content": content})

    def clear(self):
        """清除对话历史，保留上下文检测结果"""
        self.messages.clear()
        self.cached_diff = None
        self.cached_review = None
        self.cached_ci_log = None
```

## REPL 生命周期

```
启动 argus
    │
    ▼
┌─────────────────────┐
│  1. 自动上下文检测     │
│  - git repo?         │
│  - 当前分支?          │
│  - 有关联 PR?         │
│  - CI 状态?           │
│  - argus.md 存在?     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  2. 欢迎信息          │
│  显示检测到的上下文    │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  3. 输入循环          │◄──────┐
│  - 自然语言 → LLM     │       │
│  - /命令 → 直接处理    │       │
│  - Ctrl+C → 中断当前  │       │
└──────────┬──────────┘       │
           │                   │
           ▼                   │
┌─────────────────────┐       │
│  4. 处理 & 流式输出   │───────┘
└──────────┬──────────┘
           │ /exit 或 Ctrl+D
           ▼
┌─────────────────────┐
│  5. 退出              │
└─────────────────────┘
```

## 流式输出

ConversationAgent 使用 LLM 的 streaming API，将响应实时输出到终端：

```python
async def stream_response(self, session: Session) -> str:
    """流式调用 LLM 并实时输出"""
    full_response = ""
    async with self.client.messages.stream(
        model=self.model,
        system=self.system_prompt,
        messages=session.messages,
        tools=self.tools,
    ) as stream:
        async for event in stream:
            if event.type == "content_block_delta":
                chunk = event.delta.text
                print(chunk, end="", flush=True)
                full_response += chunk
            elif event.type == "content_block_start" and event.content_block.type == "tool_use":
                # 工具调用时显示状态提示
                print(f"\n⏳ 正在执行 {event.content_block.name}...", flush=True)

    print()  # 换行
    return full_response
```

工具调用期间显示 spinner（使用 rich），完成后替换为结果摘要。

## 斜杠命令

斜杠命令在 REPL 层直接处理，不经过 LLM：

| 命令 | 说明 | 实现 |
|------|------|------|
| `/review` | 审查当前分支的变更 | 获取 diff → 调用 ReviewAgent |
| `/review --staged` | 只审查暂存区 | `git diff --staged` → ReviewAgent |
| `/diagnose` | 分析当前 PR 的 CI 失败 | 获取 CI 日志 → CICDAgent |
| `/fix` | 基于最近诊断结果生成修复 | 调用 FixAgent → 展示 patch → 确认 apply |
| `/context` | 显示当前检测到的上下文 | 打印 Session 信息 |
| `/clear` | 清除对话历史 | `session.clear()` |
| `/help` | 显示帮助信息 | 打印命令列表 |
| `/exit` | 退出 REPL | 退出进程 |

斜杠命令本质是快捷方式——`/review` 等价于告诉 LLM "请审查当前分支的代码变更"，但跳过了意图理解步骤，直接执行。

```python
SLASH_COMMANDS = {
    "/review": handle_review,
    "/diagnose": handle_diagnose,
    "/context": handle_context,
    "/clear": handle_clear,
    "/help": handle_help,
    "/exit": handle_exit,
}

async def process_input(user_input: str, session: Session):
    """处理用户输入：斜杠命令直接执行，自然语言交给 LLM"""
    cmd = user_input.strip().split()[0] if user_input.strip() else ""
    if cmd in SLASH_COMMANDS:
        await SLASH_COMMANDS[cmd](user_input, session)
    else:
        session.add_message("user", user_input)
        await conversation_agent.stream_response(session)
```

## 实现要点

### 上下文自动检测

启动时自动检测环境，减少用户手动输入：

```python
async def detect_context() -> Session:
    session = Session(messages=[], ...)

    # 检测 git 仓库
    try:
        session.repo_root = run("git rev-parse --show-toplevel")
        session.git_branch = run("git rev-parse --abbrev-ref HEAD")
    except subprocess.CalledProcessError:
        pass  # 不在 git 仓库中

    # 检测关联 PR（通过 gh CLI 或 GitHub API）
    if session.git_branch:
        try:
            pr_json = run(f"gh pr view --json number,title --jq '.number'")
            session.pr_number = int(pr_json)
        except Exception:
            pass

    return session
```

### 对话历史管理

- 对话历史保存在 Session.messages 中
- 超过 token 上限时自动截断旧消息（保留 system prompt 和最近 N 轮）
- `/clear` 可手动清除

### 错误处理

| 场景 | 处理方式 |
|------|---------|
| 不在 git 仓库中 | 提示用户，仍可使用（手动提供 diff/日志） |
| LLM API 调用失败 | 显示错误信息，不中断 REPL |
| 工具执行失败 | 将错误信息返回给 LLM，让它生成用户友好的提示 |
| 用户 Ctrl+C | 中断当前 LLM 调用，回到输入提示符 |
