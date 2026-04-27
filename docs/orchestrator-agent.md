# Orchestrator Agent 详细设计

## 定位

Orchestrator 是系统的**中央调度器**。它不是一个"智能 Agent"，而是一个**确定性的事件路由器**。

为什么不用 LLM？因为 GitHub 事件类型是有限的（PR opened, CI failed, push 等），用 if/else 就能覆盖所有场景。用 LLM 做路由增加延迟、增加成本、增加不确定性，属于过度设计。

## 核心职责

```
1. 接收 GitHub Webhook 事件
2. 根据事件类型决定调度策略
3. 派发任务给对应 Agent
4. 等待 Agent 返回结果
5. 聚合、格式化、发送到 GitHub
```

## 调度规则

| 事件类型 | 触发条件 | 调度策略 |
|---------|---------|---------|
| `pull_request.opened` | PR 创建 | → Review Agent |
| `pull_request.synchronize` | PR 有新 push | → Review Agent (增量审查) |
| `check_run.completed` | CI 完成且失败 | → CI/CD Agent |
| PR opened + CI failed | PR 创建后 CI 也挂了 | → 并行派 Review + CI/CD |

## 详细实现规格

### 输入

```python
@dataclass
class GitHubEvent:
    type: str                    # "pull_request" | "check_run"
    action: str                  # "opened" | "synchronize" | "completed"
    repo: str                    # "owner/repo"
    pr_number: int | None
    diff_url: str | None         # PR diff 的 URL
    build_log_url: str | None    # CI 日志 URL
    conclusion: str | None       # CI 结论: "success" | "failure"
    installation_id: int         # GitHub App installation ID
```

### 输出

```python
@dataclass
class OrchestratorResult:
    event_type: str
    agents_invoked: list[str]         # ["review", "cicd"]
    results: list[AgentOutput]
    formatted_comment: str             # 最终发到 GitHub 的 Markdown
    execution_time_ms: int
```

### 调度逻辑伪代码

```python
class Orchestrator:
    def __init__(self, review_agent: ReviewAgent, cicd_agent: CICDAgent, github: GitHubClient):
        self.review_agent = review_agent
        self.cicd_agent = cicd_agent
        self.github = github

    async def handle(self, event: GitHubEvent) -> OrchestratorResult:
        start = time.monotonic()
        tasks: list[tuple[str, Coroutine]] = []

        # 规则路由
        if event.type == "pull_request" and event.action in ("opened", "synchronize"):
            diff = await self.github.get_pr_diff(event.repo, event.pr_number)
            tasks.append(("review", self.review_agent.run(diff)))

        if event.type == "check_run" and event.conclusion == "failure":
            log = await self.github.get_build_log(event.build_log_url)
            tasks.append(("cicd", self.cicd_agent.run(log)))

        # 并行执行
        results = {}
        if tasks:
            agent_names, coroutines = zip(*tasks)
            outputs = await asyncio.gather(*coroutines, return_exceptions=True)
            for name, output in zip(agent_names, outputs):
                if isinstance(output, Exception):
                    results[name] = AgentOutput.error(str(output))
                else:
                    results[name] = output

        # 聚合格式化
        comment = self.format_comment(results)

        # 发送到 GitHub
        if event.pr_number:
            await self.github.create_pr_comment(event.repo, event.pr_number, comment)

        elapsed = int((time.monotonic() - start) * 1000)
        return OrchestratorResult(
            event_type=event.type,
            agents_invoked=list(results.keys()),
            results=list(results.values()),
            formatted_comment=comment,
            execution_time_ms=elapsed,
        )

    def format_comment(self, results: dict[str, AgentOutput]) -> str:
        """将多个 Agent 结果合并为一条 GitHub comment"""
        sections = []

        if "review" in results:
            sections.append("## 🔍 Code Review\n" + results["review"].to_markdown())

        if "cicd" in results:
            sections.append("## 🔧 CI/CD Analysis\n" + results["cicd"].to_markdown())

        return "\n\n---\n\n".join(sections) + "\n\n> _Powered by Argus_"
```

## 错误处理策略

| 场景 | 处理方式 |
|------|---------|
| 单个 Agent 超时 (>60s) | 取消该 Agent，返回其他 Agent 的结果 + 超时提示 |
| 单个 Agent 报错 | 捕获异常，在 comment 中标注该部分不可用 |
| 所有 Agent 都失败 | 发送一条"Argus 暂时无法分析"的 comment |
| Webhook 签名验证失败 | 直接返回 401，不进入调度 |
| GitHub API 限流 | 指数退避重试，最多 3 次 |

## 面试关键问答准备

**Q: 为什么 Orchestrator 不用 LLM？**
A: 事件类型有限且确定，用规则路由延迟低、成本零、100% 可靠。LLM 路由在这里是过度设计。如果未来事件类型变得模糊（比如需要理解 PR 内容才能决定派谁），再考虑引入 LLM。

**Q: 如果要加新的 Agent（比如安全扫描 Agent），改动大吗？**
A: 只需要在 Orchestrator 的路由规则里加一条，再注册新 Agent 实例。现有 Agent 零改动。这就是 Orchestrator 模式的价值——集中管理调度策略。
