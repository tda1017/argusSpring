# 用户体验关键设计

> 这份文档解决一个问题：怎么让 Argus 从"能跑"变成"好用"。
> 技术再牛，用户觉得烦就等于零。

## 一、增量审查（最重要）

### 问题

用户在 PR 上 push 了 3 次 commit。如果每次都全量审查，同一个问题会被评论 3 次。用户第二次就会 mute Argus。

### 解决方案

**记住已审查的内容，只审查增量。**

```python
# src/state/review_state.py
import hashlib
from dataclasses import dataclass

@dataclass
class ReviewState:
    """记录一个 PR 的审查状态"""
    pr_key: str                           # "owner/repo#123"
    reviewed_commits: list[str]           # 已审查过的 commit SHA 列表
    posted_issues: list[PostedIssue]      # 已发出的评论

@dataclass
class PostedIssue:
    file: str
    line: int
    content_hash: str     # 评论内容的 hash，用于去重
    comment_id: int       # GitHub comment ID，用于后续更新/删除

class ReviewStateStore:
    """
    存储审查状态。
    Phase 1: 用本地 JSON 文件
    Phase 2: 用 SQLite
    Phase 3: 用 Redis (多实例部署)
    """

    def get_state(self, pr_key: str) -> ReviewState | None:
        ...

    def save_state(self, state: ReviewState):
        ...
```

### 增量审查流程

```python
async def incremental_review(self, event: GitHubEvent) -> ReviewOutput:
    pr_key = f"{event.repo}#{event.pr_number}"
    state = self.state_store.get_state(pr_key)

    if state is None:
        # 首次审查：全量
        diff = await self.github.get_pr_diff(event.repo, event.pr_number)
        result = await self.review_agent.run(diff)
    else:
        # 增量：只审查新 commit 引入的变更
        new_commits = await self.github.get_pr_commits_since(
            event.repo, event.pr_number, since=state.reviewed_commits[-1]
        )
        if not new_commits:
            return ReviewOutput(summary="No new changes to review.", issues=[])

        # 获取增量 diff
        diff = await self.github.get_compare_diff(
            event.repo,
            base=state.reviewed_commits[-1],
            head=new_commits[-1],
        )
        result = await self.review_agent.run(diff)

        # 去重：如果新 issues 和已发出的 issues 重复，跳过
        result.issues = self.deduplicate(result.issues, state.posted_issues)

    # 更新状态
    self.state_store.save_state(ReviewState(
        pr_key=pr_key,
        reviewed_commits=[...(state.reviewed_commits if state else []), event.head_sha],
        posted_issues=[...(state.posted_issues if state else []), *result.issues],
    ))

    return result

def deduplicate(self, new_issues: list, posted: list) -> list:
    """去重：同一个文件同一行相同内容的评论不重复发"""
    posted_hashes = {p.content_hash for p in posted}
    return [
        issue for issue in new_issues
        if self._hash_issue(issue) not in posted_hashes
    ]
```

### 自动解决过期评论

如果用户根据建议修了代码，之前的评论应该自动标记为已解决：

```python
async def resolve_fixed_comments(self, event: GitHubEvent, state: ReviewState):
    """检查已有评论对应的问题是否在新 commit 中被修复"""
    for posted in state.posted_issues:
        # 检查该行是否被修改了
        if self._is_line_changed(event.diff, posted.file, posted.line):
            # 标记为 resolved
            await self.github.resolve_review_thread(event.repo, posted.comment_id)
```

---

## 二、噪音控制

### 问题

Agent 生成了 15 条建议，其中 2 条有价值，13 条是"建议加注释"级别的废话。用户看到 15 条评论，直接崩溃。

### 解决方案：严格的发布阈值

```python
# src/agents/review.py — 输出后过滤

class ReviewPostFilter:
    """控制实际发出的评论数量和质量"""

    # 硬性上限
    MAX_COMMENTS_PER_PR = 8
    MAX_COMMENTS_PER_FILE = 3

    # 严重性阈值：默认只发 critical 和 warning，不发 suggestion
    SEVERITY_THRESHOLD = "warning"  # "critical" | "warning" | "suggestion"

    def filter(self, issues: list[ReviewIssue]) -> list[ReviewIssue]:
        # 1. 按严重性过滤
        issues = [i for i in issues if self._meets_threshold(i.severity)]

        # 2. 按文件分组，每个文件最多 MAX_COMMENTS_PER_FILE 条
        by_file = groupby(issues, key=lambda i: i.file)
        filtered = []
        for file, file_issues in by_file:
            # 每个文件取最严重的 N 条
            sorted_issues = sorted(file_issues, key=lambda i: self._severity_rank(i))
            filtered.extend(sorted_issues[:self.MAX_COMMENTS_PER_FILE])

        # 3. 总数不超过 MAX_COMMENTS_PER_PR
        filtered = sorted(filtered, key=lambda i: self._severity_rank(i))
        return filtered[:self.MAX_COMMENTS_PER_PR]
```

### 在 argus.md 中可配置

```markdown
## 审查噪音控制
- max_comments: 5          # 单次审查最多评论数
- severity_threshold: warning  # 最低发布严重性
- ignore_categories: [style]   # 忽略风格类建议
```

### 原则

```
宁可漏掉 3 条 suggestion，也不要多发 1 条垃圾 critical。

用户信任是不可再生资源——一旦觉得"Argus 老是说废话"，就再也不会认真看了。
```

---

## 三、反馈机制

### 问题

Argus 说了一条垃圾建议，用户无法告诉它"这条没用"。没有反馈 = 永远不会改进。

### 解决方案：Emoji 反馈

GitHub 原生支持对评论加 reaction。利用这个：

```
👍 = 有用的建议
👎 = 垃圾建议
😕 = 看不懂 / 不相关
```

**实现**: 定期轮询 Argus 发出的评论的 reactions。

```python
# src/feedback/collector.py

async def collect_feedback(github: GitHubClient, state: ReviewState):
    """收集用户对 Argus 评论的反馈"""
    for posted in state.posted_issues:
        reactions = await github.get_comment_reactions(posted.comment_id)
        feedback = FeedbackRecord(
            issue_hash=posted.content_hash,
            thumbs_up=reactions.get("+1", 0),
            thumbs_down=reactions.get("-1", 0),
            confused=reactions.get("confused", 0),
        )
        store_feedback(feedback)
```

### 反馈的用途

1. **短期**: 如果一个 PR 上 Argus 的评论被连续 👎，后续评论降低置信度阈值（发更少的评论）
2. **中期**: 统计哪类问题经常被 👎，调整 system prompt 权重
3. **长期**: 反馈数据可以用于 few-shot 微调 prompt

### 评测时也能用

在 `metrics.md` 的评测中，用户反馈是最有价值的真实指标：

```
用户满意度 = 👍 数 / (👍 + 👎 + 😕) 数
目标: >= 70%
```

---

## 四、可观测性

### 问题

Argus 给了一条看起来不对的建议。怎么 debug？它看了哪些文件？RAG 检索了什么？LLM 推理了几轮？

### 解决方案：结构化 Trace

每次 Agent 执行都生成一个完整的 trace：

```python
# src/observability/trace.py
from dataclasses import dataclass, field
import time
import uuid

@dataclass
class Span:
    name: str
    start_time: float
    end_time: float | None = None
    attributes: dict = field(default_factory=dict)
    children: list["Span"] = field(default_factory=list)

@dataclass
class AgentTrace:
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    agent: str = ""                      # "review" | "cicd"
    event_type: str = ""
    repo: str = ""
    pr_number: int | None = None
    spans: list[Span] = field(default_factory=list)

    # Token 用量
    total_input_tokens: int = 0
    total_output_tokens: int = 0

    # Tool calls 记录
    tool_calls: list[dict] = field(default_factory=list)
    # [{"name": "get_file_content", "args": {...}, "result_preview": "...", "duration_ms": 123}]

    # LLM 调用轮次
    llm_rounds: int = 0

    # RAG 检索记录
    rag_queries: list[dict] = field(default_factory=list)
    # [{"query": "...", "results": [...], "reranked": [...]}]
```

### 在 CLI 中查看 Trace

```bash
# 正常模式：只看结果
$ argus review --diff-file ./changes.diff

# Verbose 模式：看完整 trace
$ argus review --diff-file ./changes.diff --verbose

🔍 Argus Code Review (trace: a3f8c2d1)
━━━━━━━━━━━━━━━━━━━━

[Trace] LLM rounds: 3
[Trace] Tool calls:
  1. get_file_content("src/auth.py") → 245 lines, 82ms
  2. search_codebase("authentication middleware") → 3 results, 156ms
  3. get_project_rules("error-handling") → found rules, 43ms
[Trace] Tokens: 3,421 in / 1,287 out ($0.018)
[Trace] Total time: 8.3s

📄 src/auth/login.py
  🔴 [Critical] Line 42: SQL Injection Risk
  ...
```

### 在 GitHub Comment 中附 Trace 链接

```markdown
## 🔍 Code Review

...审查意见...

---
<details>
<summary>🔬 Debug info (trace: a3f8c2d1)</summary>

- Agent: review
- LLM rounds: 3
- Tools called: get_file_content, search_codebase, get_project_rules
- Tokens: 3,421 in / 1,287 out
- Duration: 8.3s
- RAG hits: 3 code snippets, 1 rule document
</details>

> _Powered by Argus_ · 👍 Helpful · 👎 Not helpful
```

### Token 用量追踪

```python
# src/observability/cost.py

TOKEN_COSTS = {
    "claude-sonnet-4-20250514": {"input": 3.0 / 1_000_000, "output": 15.0 / 1_000_000},
    "claude-opus-4-20250514": {"input": 15.0 / 1_000_000, "output": 75.0 / 1_000_000},
    "gpt-4o": {"input": 2.5 / 1_000_000, "output": 10.0 / 1_000_000},
}

def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float:
    costs = TOKEN_COSTS.get(model, {"input": 0, "output": 0})
    return input_tokens * costs["input"] + output_tokens * costs["output"]

# 每次 Agent 执行后记录
# 月度汇总：这个月 Argus 花了多少钱
```

---

## 五、首次使用体验 (Onboarding)

### 问题

用户 clone 了项目，然后呢？README 太长不想看。

### 30 秒上手流程

```bash
# 1. 安装
pip install argus-ai   # 或 pipx install argus-ai

# 2. 配置（交互式）
argus config
# → 选 provider, 填 API key, 完成

# 3. 试一下
cd your-project
git diff main | argus review
# → 立刻看到审查结果

# 4. (可选) 创建项目规范
argus init
# → 生成 argus.md 模板，按需编辑
```

### 不要在第一步就要求用户配 GitHub App

GitHub App 配置复杂（创建 App、生成 key、配 webhook、搞 ngrok），放在 Phase 3。

**Phase 1 的用户路径**: `pip install → argus config → git diff | argus review`

三步出结果，不需要 GitHub，不需要 webhook，不需要 Docker。
