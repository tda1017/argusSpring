"""Argus 的核心结构化数据模型。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


Severity = Literal["critical", "warning", "suggestion"]
ReviewCategory = Literal["bug", "security", "performance", "maintainability"]
Approval = Literal["approve", "request_changes", "comment"]
ErrorType = Literal["compilation", "test", "dependency", "config", "timeout", "resource"]
Confidence = Literal["high", "medium", "low"]
CIStatusType = Literal["success", "failure", "pending", "none"]


class DiffLine(BaseModel):
    """统一 diff 里的单行信息。"""

    line_number: int
    content: str


class DiffHunk(BaseModel):
    """统一 diff 的一个 hunk。"""

    header: str
    added_lines: list[DiffLine] = Field(default_factory=list)
    removed_lines: list[DiffLine] = Field(default_factory=list)
    context_lines: list[DiffLine] = Field(default_factory=list)
    raw_lines: list[str] = Field(default_factory=list)

    @property
    def text(self) -> str:
        return "\n".join([self.header, *self.raw_lines]).strip()


class DiffFile(BaseModel):
    """统一 diff 的单文件视图。"""

    path: str
    old_path: str | None = None
    new_path: str | None = None
    hunks: list[DiffHunk] = Field(default_factory=list)
    is_new: bool = False
    is_deleted: bool = False

    @property
    def text(self) -> str:
        chunks = [f"diff --git a/{self.old_path or self.path} b/{self.new_path or self.path}"]
        for hunk in self.hunks:
            chunks.append(hunk.text)
        return "\n".join(chunks).strip()

    @property
    def line_count(self) -> int:
        return sum(len(hunk.raw_lines) for hunk in self.hunks)


class DiffChunk(BaseModel):
    """给 Agent 的 diff 分块。"""

    file: str
    content: str
    start_line: int | None = None


class ReviewInput(BaseModel):
    """Review Agent 输入。"""

    repo: str | None = None
    pr_number: int | None = None
    diff: str
    changed_files: list[str] = Field(default_factory=list)
    pr_title: str = ""
    pr_body: str = ""
    ref: str | None = None
    repo_root: str | None = None


class ReviewIssue(BaseModel):
    """结构化审查问题。"""

    file: str
    line: int
    severity: Severity
    category: ReviewCategory
    description: str
    suggestion: str
    code_snippet: str | None = None


class ReviewOutput(BaseModel):
    """Review Agent 输出。"""

    summary: str
    issues: list[ReviewIssue] = Field(default_factory=list)
    approval: Approval

    def to_markdown(self) -> str:
        if not self.issues:
            return f"- ✅ {self.summary}\n- 结论: `{self.approval}`"

        lines = [f"- **总结**: {self.summary}"]
        for issue in self.issues:
            icon = {"critical": "🔴", "warning": "🟡", "suggestion": "🔵"}[issue.severity]
            lines.append(f"- {icon} `{issue.file}:{issue.line}` [{issue.category}] {issue.description}")
            lines.append(f"  建议: {issue.suggestion}")
        lines.append(f"- **结论**: `{self.approval}`")
        return "\n".join(lines)


class ErrorTrace(BaseModel):
    """结构化错误堆栈。"""

    type: str
    message: str
    file: str | None = None
    line: int | None = None


class CICDInput(BaseModel):
    """CI/CD Agent 输入。"""

    repo: str | None = None
    run_id: int | None = None
    run_url: str = ""
    job_name: str | None = None
    conclusion: str = "failure"
    head_sha: str = ""
    log_text: str | None = None
    repo_root: str | None = None


class CICDOutput(BaseModel):
    """CI/CD Agent 输出。"""

    root_cause: str
    error_type: ErrorType
    relevant_log_lines: list[str] = Field(default_factory=list)
    affected_files: list[str] = Field(default_factory=list)
    fix_suggestion: str
    fix_confidence: Confidence
    debugging_steps: list[str] = Field(default_factory=list)

    def to_markdown(self) -> str:
        lines = [f"- **根因**: {self.root_cause}", f"- **类型**: `{self.error_type}`"]
        if self.affected_files:
            lines.append(f"- **相关文件**: {', '.join(f'`{item}`' for item in self.affected_files)}")
        if self.relevant_log_lines:
            lines.append("- **关键日志**:")
            lines.extend(f"  - `{line}`" for line in self.relevant_log_lines[:5])
        lines.append(f"- **建议**: {self.fix_suggestion}")
        lines.append(f"- **置信度**: `{self.fix_confidence}`")
        if self.debugging_steps:
            lines.append("- **排查步骤**:")
            lines.extend(f"  - {step}" for step in self.debugging_steps)
        return "\n".join(lines)


class FilePatch(BaseModel):
    """单文件修复补丁。"""

    path: str
    original: str
    patched: str
    explanation: str
    issue_ref: str


class FixInput(BaseModel):
    """Fix Agent 输入。"""

    diagnosis: ReviewOutput | CICDOutput
    file_contents: dict[str, str] = Field(default_factory=dict)
    repo_root: str | None = None
    rules: str | None = None


class FixOutput(BaseModel):
    """Fix Agent 输出。"""

    patches: list[FilePatch] = Field(default_factory=list)
    summary: str
    unfixable: list[str] = Field(default_factory=list)
    confidence: float = 0.0

    def to_markdown(self) -> str:
        lines = [f"- **摘要**: {self.summary}", f"- **置信度**: `{self.confidence:.2f}`"]
        if self.patches:
            lines.append("- **补丁**:")
            for patch in self.patches:
                lines.append(f"  - `{patch.path}`: {patch.explanation}")
        if self.unfixable:
            lines.append("- **无法自动修复**:")
            lines.extend(f"  - {item}" for item in self.unfixable)
        return "\n".join(lines)


class GitContext(BaseModel):
    """本地 git 上下文。"""

    repo_root: str | None = None
    branch: str | None = None
    staged_diff: str | None = None
    recent_commits: list[str] = Field(default_factory=list)
    has_uncommitted: bool = False


class PRInfo(BaseModel):
    """当前分支关联的 PR 信息。"""

    pr_number: int | None = None
    title: str = ""
    body: str = ""
    diff: str | None = None
    state: str = "unknown"
    checks_status: str = "none"


class FailedJob(BaseModel):
    """失败 CI job 摘要。"""

    name: str
    log_excerpt: str


class CIStatus(BaseModel):
    """当前 PR 或分支的 CI 状态。"""

    status: CIStatusType = "none"
    failed_jobs: list[FailedJob] = Field(default_factory=list)
    run_url: str | None = None
    log_text: str | None = None


class ConversationInput(BaseModel):
    """Conversation Agent 输入。"""

    user_input: str
    session_messages: list[dict[str, str]] = Field(default_factory=list)
    repo_root: str | None = None
    git_branch: str | None = None
    pr_number: int | None = None
    cached_diff: str | None = None
    cached_ci_log: str | None = None


class ConversationOutput(BaseModel):
    """Conversation Agent 输出。"""

    reply: str


class AgentFailure(BaseModel):
    """Agent 失败时的兜底结果。"""

    agent: str
    error: str

    def to_markdown(self) -> str:
        return f"- ⚠️ `{self.agent}` 执行失败: {self.error}"


class GitHubEvent(BaseModel):
    """Webhook 解析后的统一事件。"""

    type: str
    action: str
    repo: str
    pr_number: int | None = None
    diff_url: str | None = None
    build_log_url: str | None = None
    conclusion: str | None = None
    installation_id: int = 0
    run_id: int | None = None
    head_sha: str = ""
    pr_title: str = ""
    pr_body: str = ""
    changed_files: list[str] = Field(default_factory=list)


class OrchestratorResult(BaseModel):
    """Orchestrator 输出。"""

    event_type: str
    agents_invoked: list[str]
    results: list[ReviewOutput | CICDOutput | FixOutput | AgentFailure]
    formatted_comment: str
    execution_time_ms: int


class SearchResult(BaseModel):
    """本地检索结果。"""

    content: str
    file: str
    start_line: int
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)
