# Review Agent 详细设计

## 定位

Review Agent 是系统的**代码审查核心**。它接收 PR diff，结合项目上下文（通过 RAG），输出结构化的审查意见。

这是 Phase 1 要交付的第一个 Agent，必须能独立工作。

## 核心能力

1. **Diff 分析**: 解析 unified diff，理解代码变更
2. **上下文补全**: 通过 RAG 检索变更文件的相关代码、项目规范
3. **问题检测**: 识别 bug、性能问题、安全漏洞、代码风格问题
4. **建议生成**: 给出具体的修改建议，而不是泛泛而谈

## System Prompt

```
You are Argus Review Agent, a senior code reviewer. Your job is to review pull request diffs and provide actionable feedback.

## Review Focus
1. **Bugs**: Logic errors, off-by-one, null/undefined handling, race conditions
2. **Security**: Injection, XSS, hardcoded secrets, unsafe deserialization
3. **Performance**: N+1 queries, unnecessary allocations, missing indexes
4. **Maintainability**: Overly complex logic, missing error handling, unclear naming

## Rules
- Only comment on the CHANGED lines, not existing code
- Each issue must include: file path, line number, severity, description, suggested fix
- If the code looks good, say so briefly. Don't invent problems.
- Be specific. "This might have issues" is useless. "Line 42: `users.find()` without `.limit()` will load entire collection into memory" is useful.
- Severity levels: 🔴 critical (must fix), 🟡 warning (should fix), 🔵 suggestion (nice to have)
```

## 工具定义

Review Agent 可以调用以下工具：

### Tool 1: `get_file_content`

获取仓库中某个文件的完整内容（用于理解 diff 的上下文）。

```python
{
    "name": "get_file_content",
    "description": "获取仓库中指定文件的完整内容",
    "parameters": {
        "repo": {"type": "string", "description": "owner/repo"},
        "path": {"type": "string", "description": "文件路径"},
        "ref": {"type": "string", "description": "分支或 commit SHA"}
    }
}
```

**实现**: 调用 GitHub API `GET /repos/{owner}/{repo}/contents/{path}?ref={ref}`

### Tool 2: `search_codebase`

在项目代码库中语义检索相关代码片段（RAG）。

```python
{
    "name": "search_codebase",
    "description": "语义搜索项目代码库，找到与查询相关的代码片段",
    "parameters": {
        "query": {"type": "string", "description": "搜索查询"},
        "top_k": {"type": "integer", "description": "返回结果数量", "default": 5}
    }
}
```

**实现**: 调用 RAG 子系统的 retriever，返回最相关的代码片段。

### Tool 3: `get_project_rules`

检索项目的编码规范和约定（RAG）。

```python
{
    "name": "get_project_rules",
    "description": "获取项目的编码规范、lint 规则和团队约定",
    "parameters": {
        "topic": {"type": "string", "description": "规范主题，如 'naming', 'error-handling', 'testing'"}
    }
}
```

**实现**: 从 RAG 索引中检索 README、CONTRIBUTING.md、.eslintrc、团队文档等。

## 输入/输出格式

### 输入

```python
@dataclass
class ReviewInput:
    repo: str                     # "owner/repo"
    pr_number: int
    diff: str                     # unified diff 格式的代码变更
    changed_files: list[str]      # 变更的文件列表
    pr_title: str
    pr_body: str
```

### 输出

```python
@dataclass
class ReviewIssue:
    file: str                     # 文件路径
    line: int                     # 行号
    severity: str                 # "critical" | "warning" | "suggestion"
    category: str                 # "bug" | "security" | "performance" | "maintainability"
    description: str              # 问题描述
    suggestion: str               # 修复建议
    code_snippet: str | None      # 相关代码片段

@dataclass
class ReviewOutput:
    summary: str                  # 总结性评价
    issues: list[ReviewIssue]     # 发现的问题列表
    approval: str                 # "approve" | "request_changes" | "comment"
```

## Agent 执行流程

```
1. 接收 ReviewInput
2. 解析 diff，提取变更文件列表和变更内容
3. 对每个变更文件:
   a. 调用 get_file_content 获取完整文件（理解上下文）
   b. 调用 search_codebase 检索该文件/函数被其他地方引用的情况
4. 调用 get_project_rules 获取相关编码规范
5. 将 diff + 上下文 + 规范 注入 prompt，让 LLM 分析
6. LLM 可能需要多轮 tool calling（比如需要看更多文件）
7. 解析 LLM 输出为 ReviewOutput 结构化数据
```

## Diff 解析策略

不要把整个 diff 一股脑丢给 LLM。大 PR 的 diff 可能有几千行，会超 token 限制。

**策略: 按文件分块**

```python
def chunk_diff(diff: str, max_lines_per_chunk: int = 300) -> list[DiffChunk]:
    """将大 diff 按文件拆分，每个 chunk 不超过 max_lines_per_chunk"""
    files = parse_unified_diff(diff)
    chunks = []
    for file in files:
        if len(file.lines) > max_lines_per_chunk:
            # 超大文件按 hunk 拆分
            for hunk in file.hunks:
                chunks.append(DiffChunk(file=file.path, content=hunk.text))
        else:
            chunks.append(DiffChunk(file=file.path, content=file.text))
    return chunks
```

对于超大 PR (>10 文件)，先让 LLM 看文件列表和 PR 描述，决定哪些文件值得深入审查，跳过纯格式改动、自动生成文件等。

## 实现优先级

```
P0 (Phase 1 必须):
  - 基础 diff 解析
  - 单文件审查（不需要 RAG，直接分析 diff）
  - 输出结构化 ReviewOutput

P1 (Phase 1 增强):
  - get_file_content 工具（获取完整文件上下文）
  - 多文件 PR 的分块审查

P2 (Phase 2):
  - RAG 集成（search_codebase, get_project_rules）
  - 增量审查（只审查新增的 commit，不重复审查已审查过的部分）
```

## 面试关键问答准备

**Q: 如何处理超大 PR？**
A: 按文件分块，每个 chunk 独立审查。超大 PR 先做文件级 triage，跳过自动生成文件和纯格式改动。

**Q: 如何减少误报？**
A: 三个手段：(1) RAG 检索项目规范，避免按自己的标准审查人家的代码；(2) 只评论变更行，不评论已有代码；(3) 通过历史 PR 数据微调审查标准。

**Q: 和 GitHub Copilot Code Review 的区别？**
A: Copilot 是通用审查，Argus 通过 RAG 检索项目特定的规范和代码模式，做项目感知的审查。比如它知道你项目里 error handling 统一用 Result 模式，如果有人用 try/catch 它会提醒。
