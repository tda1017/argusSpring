# CI/CD Agent 详细设计

## 定位

CI/CD Agent 负责**分析构建失败日志，定位根因，生成修复建议**。它是 Phase 2 交付的 Agent。

核心难点：CI 日志通常很长（几千到几万行），充满噪声。Agent 需要从海量日志中精准定位关键错误。

## 核心能力

1. **日志解析**: 从冗长的 CI 日志中提取关键错误信息
2. **根因分析**: 区分直接错误和级联错误，找到根本原因
3. **修复建议**: 基于错误类型和项目上下文，生成可执行的修复方案
4. **模式匹配**: 通过 RAG 检索历史上类似的失败和解决方案

## System Prompt

```
You are Argus CI/CD Agent, a build failure analyst. Your job is to analyze CI build logs, identify the root cause of failures, and suggest fixes.

## Analysis Approach
1. First, identify the FIRST error in the log (not cascading errors)
2. Classify the error type: compilation, test failure, dependency, config, timeout, resource
3. For test failures: identify which test failed and why
4. For compilation errors: identify the file and line
5. For dependency issues: identify the conflicting versions

## Rules
- Focus on the ROOT CAUSE, not symptoms. If 50 tests fail because of one broken import, the issue is the import, not 50 test failures.
- Always provide a concrete fix, not just "fix the error"
- If you're not sure about the fix, say so and provide debugging steps instead
- Quote the relevant log lines in your analysis
```

## 工具定义

### Tool 1: `get_build_log`

获取 CI 构建的完整日志。

```python
{
    "name": "get_build_log",
    "description": "获取 CI 构建的完整日志或特定 job 的日志",
    "parameters": {
        "repo": {"type": "string"},
        "run_id": {"type": "integer", "description": "GitHub Actions run ID"},
        "job_name": {"type": "string", "description": "特定 job 名称，不填则返回所有失败 job"}
    }
}
```

### Tool 2: `parse_error_trace`

从日志中提取结构化的错误信息（这是一个本地工具，不调 API）。

```python
{
    "name": "parse_error_trace",
    "description": "解析日志中的错误堆栈，返回结构化的错误链",
    "parameters": {
        "log_text": {"type": "string", "description": "原始日志文本"},
        "language": {"type": "string", "description": "项目语言: python/javascript/java/go/rust"}
    }
}
```

**实现**: 这是一个确定性的解析工具，用正则匹配各语言的错误模式：

```python
ERROR_PATTERNS = {
    "python": [
        r"(?P<type>\w+Error): (?P<message>.+)",
        r'File "(?P<file>.+)", line (?P<line>\d+)',
        r"ModuleNotFoundError: No module named '(?P<module>.+)'",
    ],
    "javascript": [
        r"(?P<type>\w+Error): (?P<message>.+)",
        r"at .+ \((?P<file>.+):(?P<line>\d+):(?P<col>\d+)\)",
        r"Cannot find module '(?P<module>.+)'",
    ],
    "java": [
        r"(?P<type>[\w.]+Exception): (?P<message>.+)",
        r"at (?P<class>[\w.]+)\((?P<file>\w+\.java):(?P<line>\d+)\)",
    ],
    "go": [
        r"(?P<file>.+\.go):(?P<line>\d+):\d+: (?P<message>.+)",
        r"FAIL\s+(?P<package>[\w/]+)",
    ],
    "rust": [
        r"error\[(?P<code>E\d+)\]: (?P<message>.+)",
        r"--> (?P<file>.+):(?P<line>\d+):(?P<col>\d+)",
    ],
}
```

### Tool 3: `search_solutions`

在错误模式库中检索类似问题的历史解决方案（RAG）。

```python
{
    "name": "search_solutions",
    "description": "搜索历史上类似的 CI 失败和解决方案",
    "parameters": {
        "error_message": {"type": "string", "description": "错误信息"},
        "top_k": {"type": "integer", "default": 3}
    }
}
```

## 输入/输出格式

### 输入

```python
@dataclass
class CICDInput:
    repo: str
    run_id: int
    run_url: str                    # GitHub Actions run URL
    job_name: str | None
    conclusion: str                 # "failure" | "timed_out"
    head_sha: str                   # 触发 CI 的 commit
```

### 输出

```python
@dataclass
class CICDOutput:
    root_cause: str                  # 根因描述
    error_type: str                  # "compilation" | "test" | "dependency" | "config" | "timeout" | "resource"
    relevant_log_lines: list[str]    # 关键日志行
    affected_files: list[str]        # 相关文件
    fix_suggestion: str              # 修复建议
    fix_confidence: str              # "high" | "medium" | "low"
    debugging_steps: list[str]       # 如果 confidence 低，给出调试步骤
```

## 日志处理策略

CI 日志最大的挑战是**太长**。一个完整的 GitHub Actions 日志可能有 10000+ 行，直接丢给 LLM 会超 token 限制。

### 日志裁剪流水线

```python
def preprocess_log(raw_log: str, max_lines: int = 500) -> str:
    """将超长日志裁剪到 LLM 可处理的大小"""
    lines = raw_log.splitlines()

    # Step 1: 移除 timestamp 前缀和 ANSI 颜色码
    lines = [strip_ansi(strip_timestamp(line)) for line in lines]

    # Step 2: 折叠重复行（比如 100 行 "Downloading package..."）
    lines = collapse_repeated(lines, threshold=3)

    # Step 3: 保留错误相关区域
    # 找到所有包含 error/fail/exception 的行，保留其前后 10 行
    error_regions = find_error_regions(lines, context=10)

    # Step 4: 如果 error_regions 足够，只保留这些区域
    if error_regions:
        result = merge_regions(error_regions)
        if len(result) <= max_lines:
            return "\n".join(result)

    # Step 5: fallback — 保留前 50 行(环境信息) + 最后 200 行(通常含错误)
    return "\n".join(lines[:50] + ["... (truncated) ..."] + lines[-200:])
```

## Agent 执行流程

```
1. 接收 CICDInput
2. 调用 get_build_log 获取失败 job 的日志
3. 调用 parse_error_trace 提取结构化错误（确定性解析，不用 LLM）
4. 将裁剪后的日志 + 结构化错误信息注入 prompt
5. LLM 分析根因
6. 如果 LLM 需要更多信息:
   a. 调用 search_solutions 检索类似问题 (RAG)
   b. 调用 get_file_content (复用 Review Agent 的工具) 查看出错的源文件
7. 输出 CICDOutput
```

## 实现优先级

```
P0 (Phase 2 必须):
  - 日志获取和预处理流水线
  - parse_error_trace（Python + JavaScript 两种语言）
  - 基础根因分析

P1 (Phase 2 增强):
  - 更多语言的错误模式
  - search_solutions RAG 集成

P2 (Phase 3):
  - 自动生成修复 PR
  - 和 Review Agent 协作（Review Agent 指出问题 → CI/CD Agent 验证修复是否解决 CI 失败）
```

## 面试关键问答准备

**Q: 如何处理超长日志？**
A: 四步裁剪：去噪（timestamp/ANSI）→ 折叠重复行 → 提取错误区域（error/fail/exception 前后 10 行）→ fallback 保留首尾。确保不超 token 限制。

**Q: parse_error_trace 为什么不用 LLM？**
A: 错误堆栈格式是固定的，正则匹配比 LLM 更快、更准、零成本。LLM 用在需要"理解"的地方（分析根因），不用在模式匹配这种确定性任务上。

**Q: 如何区分根因和级联错误？**
A: 关键策略是找"第一个错误"。日志是时序的，第一个错误往往是根因。parse_error_trace 返回的错误链是有序的，优先分析最早出现的错误。
