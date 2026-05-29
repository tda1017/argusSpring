# 工具接口定义

## 概述

Argus 的 Agent 通过 **Function Calling** 调用工具。每个工具是一个独立的 Python 函数，Agent 基类负责工具注册和调用分发。

## 工具注册机制

```python
from dataclasses import dataclass
from typing import Any, Callable

@dataclass
class ToolDefinition:
    name: str
    description: str
    parameters: dict          # JSON Schema
    handler: Callable         # 实际执行函数

class ToolRegistry:
    def __init__(self):
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition):
        self._tools[tool.name] = tool

    def get_schemas(self) -> list[dict]:
        """生成 LLM API 需要的 tools 参数"""
        return [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.parameters,
            }
            for t in self._tools.values()
        ]

    async def execute(self, name: str, arguments: dict) -> Any:
        tool = self._tools[name]
        return await tool.handler(**arguments)
```

## 全部工具列表

### GitHub 相关工具

#### `get_pr_diff`

```python
name: "get_pr_diff"
description: "获取 Pull Request 的 diff 内容"
parameters:
  repo: string       # "owner/repo"
  pr_number: integer
returns: string      # unified diff 格式
```

实现:
```python
async def get_pr_diff(repo: str, pr_number: int) -> str:
    url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}"
    headers = {"Accept": "application/vnd.github.v3.diff"}
    response = await github_client.get(url, headers=headers)
    return response.text
```

#### `get_file_content`

```python
name: "get_file_content"
description: "获取仓库中指定文件的完整内容"
parameters:
  repo: string
  path: string       # 文件路径
  ref: string        # 分支或 commit SHA (默认为 PR 的 head)
returns: string      # 文件内容
```

#### `get_build_log`

```python
name: "get_build_log"
description: "获取 GitHub Actions 构建日志"
parameters:
  repo: string
  run_id: integer
  job_name: string | null  # 不填返回所有失败 job
returns: string            # 日志文本 (已预处理)
```

实现关键: 日志需要先经过 `preprocess_log()` 裁剪再返回给 Agent。

#### `create_pr_comment`

```python
name: "create_pr_comment"
description: "在 PR 上发送评论"
parameters:
  repo: string
  pr_number: integer
  body: string       # Markdown 格式
returns: string      # comment URL
```

#### `create_review_comment`

```python
name: "create_review_comment"
description: "在 PR 的特定代码行上留评论 (inline comment)"
parameters:
  repo: string
  pr_number: integer
  path: string       # 文件路径
  line: integer      # 行号
  body: string
returns: string      # comment URL
```

### RAG 相关工具

#### `search_codebase`

```python
name: "search_codebase"
description: "语义搜索项目代码库，找到与查询相关的代码片段"
parameters:
  query: string
  top_k: integer     # 默认 5
returns: list[{content: string, file: string, start_line: int, score: float}]
```

#### `get_project_rules`

```python
name: "get_project_rules"
description: "获取项目的编码规范和团队约定"
parameters:
  topic: string      # "naming" | "error-handling" | "testing" | "general"
returns: string      # 相关规范文本
```

#### `search_solutions`

```python
name: "search_solutions"
description: "搜索历史上类似的 CI 失败和解决方案"
parameters:
  error_message: string
  top_k: integer     # 默认 3
returns: list[{error: string, solution: string, repo: string, date: string}]
```

### 代码分析工具

#### `parse_error_trace`

```python
name: "parse_error_trace"
description: "解析日志中的错误堆栈，返回结构化错误链"
parameters:
  log_text: string
  language: string   # "python" | "javascript" | "java" | "go" | "rust"
returns: list[{type: string, message: string, file: string, line: int}]
```

**注意**: 这是确定性工具（正则匹配），不调用 LLM。

## 工具与 Agent 的映射

| 工具 | Review Agent | CI/CD Agent | Fix Agent | ConversationAgent | Orchestrator |
|------|:-----------:|:-----------:|:---------:|:-----------------:|:------------:|
| get_pr_diff | ✅ | - | - | - | - |
| get_file_content | ✅ | ✅ | ✅ | - | - |
| get_build_log | - | ✅ | - | - | - |
| create_pr_comment | - | - | - | - | ✅ (内部调用) |
| create_review_comment | - | - | - | - | ✅ (内部调用) |
| search_codebase | ✅ | - | ✅ | - | - |
| get_project_rules | ✅ | - | - | - | - |
| search_solutions | - | ✅ | - | - | - |
| parse_error_trace | - | ✅ | - | - | - |
| review | - | - | - | ✅ | - |
| diagnose | - | - | - | ✅ | - |
| fix | - | - | - | ✅ | - |
| get_git_context | - | - | - | ✅ | - |
| get_pr_info | - | - | - | ✅ | - |
| get_ci_status | - | - | - | ✅ | - |

- Orchestrator 不暴露工具给 LLM（它不用 LLM），而是在代码中直接调用 `create_pr_comment`。
- ConversationAgent 的工具是**高层编排工具**（调用子 Agent），不直接操作 GitHub API。
- Fix Agent 需要读取文件内容和搜索代码库来生成精确的修复补丁。

### ConversationAgent 专用工具

以下工具仅由 ConversationAgent 使用，用于编排子 Agent 和获取本地上下文。

#### `review`

```python
name: "review"
description: "调用 Review Agent 审查代码变更"
parameters:
  diff: string           # unified diff 内容
  rules: string | null   # 项目规范 (来自 argus.md)
returns: ReviewOutput    # 结构化审查结果
```

#### `diagnose`

```python
name: "diagnose"
description: "调用 CI/CD Agent 分析构建日志"
parameters:
  log: string            # 构建日志内容
returns: CICDOutput      # 结构化诊断结果
```

#### `fix`

```python
name: "fix"
description: "调用 Fix Agent 基于诊断结果生成修复补丁"
parameters:
  diagnosis: object      # ReviewOutput 或 CICDOutput
  file_paths: list[string] | null  # 需要修复的文件路径（可选）
returns: FixOutput       # 修复补丁列表
```

#### `get_git_context`

```python
name: "get_git_context"
description: "获取当前 git 仓库状态：分支、暂存区变更、最近提交"
parameters: {}
returns:
  repo_root: string
  branch: string
  staged_diff: string | null
  recent_commits: list[string]
  has_uncommitted: bool
```

实现：纯 git 命令调用，不需要 LLM。

#### `get_pr_info`

```python
name: "get_pr_info"
description: "获取当前分支对应的 PR 信息（标题、描述、diff）"
parameters:
  repo: string | null     # 不填则从 git remote 推断
  pr_number: int | null   # 不填则根据当前分支查找
returns:
  pr_number: int
  title: string
  body: string
  diff: string
  state: string
  checks_status: string
```

#### `get_ci_status`

```python
name: "get_ci_status"
description: "获取 CI/CD 构建状态，如果有失败则返回日志摘要"
parameters:
  repo: string | null
  pr_number: int | null
returns:
  status: string          # "success" | "failure" | "pending" | "none"
  failed_jobs: list[{name: string, log_excerpt: string}] | null
  run_url: string | null
```

### Fix Agent 专用工具

Fix Agent 复用 `get_file_content` 和 `search_codebase`（已在上方定义），另有一个输出工具：

#### `apply_patch`

```python
name: "apply_patch"
description: "将生成的修复补丁应用到文件"
parameters:
  patches: list[{path: string, original: string, patched: string}]
  dry_run: bool           # true 则只预览不实际修改
returns:
  applied: list[string]   # 成功应用的文件路径
  failed: list[{path: string, reason: string}]
```

**注意**: 在 REPL 模式下，`apply_patch` 默认 `dry_run=true`，展示预览后需要用户确认才实际应用。

## GitHub App 认证

所有 GitHub API 调用通过 GitHub App 认证：

```python
class GitHubAppClient:
    def __init__(self, app_id: int, private_key: str):
        self.app_id = app_id
        self.private_key = private_key

    async def get_installation_token(self, installation_id: int) -> str:
        """生成 installation access token"""
        jwt = self._create_jwt()
        response = await httpx.post(
            f"https://api.github.com/app/installations/{installation_id}/access_tokens",
            headers={"Authorization": f"Bearer {jwt}"},
        )
        return response.json()["token"]

    def _create_jwt(self) -> str:
        import jwt
        payload = {
            "iat": int(time.time()),
            "exp": int(time.time()) + 600,
            "iss": self.app_id,
        }
        return jwt.encode(payload, self.private_key, algorithm="RS256")
```
