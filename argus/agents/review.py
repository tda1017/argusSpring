"""Review Agent 实现。"""

from __future__ import annotations

import re
from pathlib import Path

from argus.agents.base import BaseAgent
from argus.agents.post_filter import ReviewPostFilter
from argus.github import GitHubClient, read_local_file
from argus.models import ReviewInput, ReviewIssue, ReviewOutput
from argus.rag import LocalRetriever
from argus.tooling import ToolDefinition
from argus.tools.diff_parser import changed_files, chunk_diff, parse_unified_diff
from argus.project_rules import find_project_rules


REVIEW_SYSTEM_PROMPT = """You are Argus Review Agent, a senior code reviewer. Your job is to review pull request diffs and provide actionable feedback.

## Review Focus
1. Bugs: Logic errors, null handling, race conditions
2. Security: Injection, XSS, hardcoded secrets, unsafe deserialization
3. Performance: N+1 queries, unnecessary allocations, missing indexes
4. Maintainability: Complex logic, missing error handling, unclear naming

## Rules
- Only comment on CHANGED lines
- Return valid JSON only
- Each issue must include file path, line number, severity, description, suggestion
- If the code looks good, say so briefly. Don't invent problems.
"""


class ReviewAgent(BaseAgent[ReviewInput, ReviewOutput]):
    """PR diff 审查 Agent。"""

    name = "review"
    system_prompt = REVIEW_SYSTEM_PROMPT

    def __init__(
        self,
        provider=None,
        repo_root: Path | None = None,
        github_client: GitHubClient | None = None,
        retriever: LocalRetriever | None = None,
    ):
        self.github = github_client
        self.retriever = retriever
        super().__init__(provider=provider, repo_root=repo_root)

    @property
    def output_model(self) -> type[ReviewOutput]:
        return ReviewOutput

    def register_tools(self) -> None:
        self.tools.register(
            ToolDefinition(
                name="get_file_content",
                description="获取仓库中指定文件的完整内容",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": "string"},
                        "path": {"type": "string"},
                        "ref": {"type": "string"},
                    },
                    "required": ["path"],
                },
                handler=self.get_file_content,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="search_codebase",
                description="语义搜索项目代码库，找到与查询相关的代码片段",
                parameters={
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "top_k": {"type": "integer", "default": 5},
                    },
                    "required": ["query"],
                },
                handler=self.search_codebase,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="get_project_rules",
                description="获取项目规范和团队约定",
                parameters={
                    "type": "object",
                    "properties": {"topic": {"type": "string"}},
                    "required": ["topic"],
                },
                handler=self.get_project_rules,
            )
        )

    def build_user_prompt(self, input_data: ReviewInput) -> str:
        chunks = chunk_diff(input_data.diff)
        chunk_text = "\n\n".join(
            f"### File: {chunk.file}\n{chunk.content}" for chunk in chunks[:20]
        )
        schema = ReviewOutput.model_json_schema()
        return (
            f"Review this pull request diff and return JSON that matches this schema:\n{schema}\n\n"
            f"PR title: {input_data.pr_title or '(none)'}\n"
            f"PR body: {input_data.pr_body or '(none)'}\n"
            f"Changed files: {', '.join(input_data.changed_files or changed_files(input_data.diff))}\n\n"
            f"Diff chunks:\n{chunk_text}"
        )

    async def fallback(self, input_data: ReviewInput) -> ReviewOutput:
        files = parse_unified_diff(input_data.diff)
        issues: list[ReviewIssue] = []
        for diff_file in files:
            added_lines = [line for hunk in diff_file.hunks for line in hunk.added_lines]
            for index, line in enumerate(added_lines):
                issue = self._inspect_line(diff_file.path, line.line_number, line.content, added_lines, index)
                if issue:
                    issues.append(issue)

        issues = self._deduplicate_issues(issues)
        if not issues:
            return ReviewOutput(summary="未发现明显的新增风险。", issues=[], approval="approve")

        critical = sum(1 for item in issues if item.severity == "critical")
        warning = sum(1 for item in issues if item.severity == "warning")
        if critical:
            approval = "request_changes"
        elif warning:
            approval = "comment"
        else:
            approval = "comment"
        summary = f"发现 {critical} 个严重问题，{warning} 个警告，建议先处理主要风险。"
        return ReviewOutput(summary=summary, issues=issues, approval=approval)

    async def postprocess(self, output: ReviewOutput, input_data: ReviewInput) -> ReviewOutput:
        rules = find_project_rules(self.repo_root)
        post_filter = ReviewPostFilter.from_rules(rules)
        filtered = post_filter.filter(output.issues)
        if not filtered and output.issues:
            return ReviewOutput(summary="检测到问题，但都低于发布阈值，已抑制噪音评论。", issues=[], approval="approve")
        approval = "approve"
        if any(item.severity == "critical" for item in filtered):
            approval = "request_changes"
        elif filtered:
            approval = "comment"
        return ReviewOutput(summary=output.summary, issues=filtered, approval=approval)

    async def get_file_content(self, path: str, repo: str | None = None, ref: str | None = None) -> str:
        if repo and self.github:
            return await self.github.get_file_content(repo, path, ref=ref)
        return await read_local_file(self.repo_root, path)

    def search_codebase(self, query: str, top_k: int = 5) -> list[dict]:
        if not self.retriever:
            return []
        return [item.model_dump() for item in self.retriever.search_codebase(query, top_k=top_k)]

    def get_project_rules(self, topic: str) -> str:
        if not self.retriever:
            rules = find_project_rules(self.repo_root)
            return rules or ""
        return self.retriever.get_project_rules(topic)

    def _inspect_line(self, file_path: str, line_number: int, content: str, added_lines: list, index: int) -> ReviewIssue | None:
        stripped = content.strip()
        lowered = stripped.lower()

        if re.search(r"(api[_-]?key|secret|token|password)\s*=\s*[\"'][^\"']+[\"']", stripped, re.IGNORECASE):
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="critical",
                category="security",
                description="疑似把敏感信息硬编码进代码。",
                suggestion="改为读取环境变量或密钥管理服务。",
                code_snippet=stripped,
            )
        if "eval(" in lowered:
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="critical",
                category="security",
                description="`eval()` 会执行动态代码，风险太高。",
                suggestion="改用显式解析逻辑，避免执行任意字符串。",
                code_snippet=stripped,
            )
        if re.search(r"execute\s*\(\s*f?[\"'].*(select|insert|update|delete)", lowered) and any(
            marker in stripped for marker in ("{", "+", ".format(")
        ):
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="critical",
                category="security",
                description="SQL 语句看起来是字符串拼接，容易引入注入风险。",
                suggestion="使用参数化查询，不要把用户输入直接拼进 SQL。",
                code_snippet=stripped,
            )
        if "verify=false" in lowered:
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="warning",
                category="security",
                description="关闭 TLS 校验会让请求暴露在中间人攻击下。",
                suggestion="保留证书校验，必要时显式配置可信 CA。",
                code_snippet=stripped,
            )
        if "shell=true" in lowered and "subprocess" in lowered:
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="critical",
                category="security",
                description="`shell=True` 会放大命令注入面。",
                suggestion="改为参数数组调用，并对输入做严格校验。",
                code_snippet=stripped,
            )
        if stripped.startswith("except Exception"):
            next_line = added_lines[index + 1].content.strip() if index + 1 < len(added_lines) else ""
            if next_line in {"pass", "return None"}:
                return ReviewIssue(
                    file=file_path,
                    line=line_number,
                    severity="warning",
                    category="maintainability",
                    description="这里吞掉了顶层异常，后续排查会很痛苦。",
                    suggestion="至少记录异常上下文，或改为捕获更具体的异常类型。",
                    code_snippet=f"{stripped}\n{next_line}".strip(),
                )
        if re.search(r"\b(print|console\.log)\s*\(", lowered):
            return ReviewIssue(
                file=file_path,
                line=line_number,
                severity="suggestion",
                category="maintainability",
                description="提交里留下了调试输出。",
                suggestion="改用正式日志或在合并前删除。",
                code_snippet=stripped,
            )
        return None

    def _deduplicate_issues(self, issues: list[ReviewIssue]) -> list[ReviewIssue]:
        seen: set[tuple[str, int, str]] = set()
        result: list[ReviewIssue] = []
        for issue in issues:
            key = (issue.file, issue.line, issue.description)
            if key in seen:
                continue
            seen.add(key)
            result.append(issue)
        return result
