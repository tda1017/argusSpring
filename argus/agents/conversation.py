"""Conversation Agent 实现。"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

from argus.agents.base import BaseAgent, _json_dump
from argus.agents.cicd import CICDAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.models import (
    CICDInput,
    CICDOutput,
    ConversationInput,
    ConversationOutput,
    FixInput,
    FixOutput,
    ReviewInput,
    ReviewOutput,
)
from argus.project_rules import find_project_rules, inject_rules_to_prompt
from argus.repl.context import collect_ci_status, collect_git_context, collect_pr_info
from argus.tooling import ToolDefinition
from argus.tools.diff_parser import changed_files


CONVERSATION_SYSTEM_PROMPT = """你是 Argus，一个交互式代码审查与 CI/CD 诊断助手。

你运行在一个 git 仓库上下文中，可以：
- 审查代码变更（调用 review 工具）
- 分析 CI 构建失败（调用 diagnose 工具）
- 基于诊断结果生成修复补丁（调用 fix 工具）
- 获取当前 git 状态、PR 信息、CI 状态

行为准则：
1. 优先使用上下文信息减少用户输入
2. 输出简洁、可操作的建议，不废话
3. 如果请求不明确，主动询问而不是猜测
4. 对于大型 diff，先给摘要，用户要求时再展开细节
5. 最终只返回 JSON，格式为 {"reply": "..."}
"""

STREAMING_SYSTEM_PROMPT = """你是 Argus，一个交互式代码审查与 CI/CD 诊断助手。

你运行在一个 git 仓库上下文中，可以：
- 审查代码变更（调用 review 工具）
- 分析 CI 构建失败（调用 diagnose 工具）
- 基于诊断结果生成修复补丁（调用 fix 工具）
- 获取当前 git 状态、PR 信息、CI 状态

行为准则：
1. 优先使用上下文信息减少用户输入
2. 输出简洁、可操作的建议，不废话
3. 如果请求不明确，主动询问而不是猜测
4. 对于大型 diff，先给摘要，用户要求时再展开细节
5. 直接用自然语言回答，不要输出 JSON，不要解释你在调用工具。
"""


class ConversationAgent(BaseAgent[ConversationInput, ConversationOutput]):
    """交互式对话编排器。"""

    name = "conversation"
    system_prompt = CONVERSATION_SYSTEM_PROMPT

    def __init__(
        self,
        review_agent: ReviewAgent,
        cicd_agent: CICDAgent,
        fix_agent: FixAgent,
        provider=None,
        repo_root: Path | None = None,
    ):
        self.review_agent = review_agent
        self.cicd_agent = cicd_agent
        self.fix_agent = fix_agent
        self._current_session = None
        super().__init__(provider=provider, repo_root=repo_root)

    @property
    def output_model(self) -> type[ConversationOutput]:
        return ConversationOutput

    def register_tools(self) -> None:
        self.tools.register(
            ToolDefinition(
                name="review",
                description="调用 Review Agent 审查代码变更",
                parameters={
                    "type": "object",
                    "properties": {
                        "diff": {"type": "string"},
                        "rules": {"type": ["string", "null"]},
                    },
                    "required": ["diff"],
                },
                handler=self.tool_review,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="diagnose",
                description="调用 CI/CD Agent 分析构建日志",
                parameters={
                    "type": "object",
                    "properties": {"log": {"type": "string"}},
                    "required": ["log"],
                },
                handler=self.tool_diagnose,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="fix",
                description="调用 Fix Agent 基于诊断结果生成修复补丁",
                parameters={
                    "type": "object",
                    "properties": {
                        "diagnosis": {"type": "object"},
                        "file_paths": {"type": ["array", "null"], "items": {"type": "string"}},
                    },
                    "required": ["diagnosis"],
                },
                handler=self.tool_fix,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="get_git_context",
                description="获取当前 git 仓库状态：分支、暂存区变更、最近提交",
                parameters={"type": "object", "properties": {}},
                handler=self.tool_get_git_context,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="get_pr_info",
                description="获取当前分支对应的 Pull Request 信息",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": ["string", "null"]},
                        "pr_number": {"type": ["integer", "null"]},
                    },
                },
                handler=self.tool_get_pr_info,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="get_ci_status",
                description="获取 CI/CD 构建状态，如果有失败则返回日志摘要",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": ["string", "null"]},
                        "pr_number": {"type": ["integer", "null"]},
                    },
                },
                handler=self.tool_get_ci_status,
            )
        )

    async def respond(
        self,
        input_data: ConversationInput,
        session,
        stream: bool = False,
        on_chunk=None,
        on_tool_start=None,
        on_tool_end=None,
    ) -> str:
        self._current_session = session
        if stream:
            return await self.stream_response(
                input_data,
                on_chunk=on_chunk,
                on_tool_start=on_tool_start,
                on_tool_end=on_tool_end,
            )
        output = await self.run(input_data)
        return output.reply

    async def stream_response(
        self,
        input_data: ConversationInput,
        on_chunk=None,
        on_tool_start=None,
        on_tool_end=None,
    ) -> str:
        self.last_trace = self.create_trace(input_data)
        if not self.provider:
            output = await self.fallback(input_data)
            if on_chunk:
                await _maybe_async(on_chunk, output.reply)
            return output.reply

        rules = find_project_rules(self.repo_root)
        system = inject_rules_to_prompt(self._streaming_system_prompt(input_data), rules)
        messages = self._streaming_messages(input_data)
        final_content = ""

        try:
            for _ in range(self.max_rounds):
                self.last_trace.llm_rounds += 1
                response = await self.provider.stream_chat(
                    messages=messages,
                    tools=self.tools.get_schemas() or None,
                    system=system,
                    on_text=on_chunk,
                    on_tool_call=on_tool_start,
                )
                self.last_trace.add_usage(
                    response.usage.get("input_tokens", 0),
                    response.usage.get("output_tokens", 0),
                )
                final_content = response.content
                if response.tool_calls:
                    messages.append(
                        {
                            "role": "assistant",
                            "content": response.content,
                            "tool_calls": [
                                {"id": call.id, "name": call.name, "arguments": call.arguments}
                                for call in response.tool_calls
                            ],
                        }
                    )
                    for tool_call in response.tool_calls:
                        started = time.monotonic()
                        result = await self.tools.execute(tool_call.name, tool_call.arguments)
                        elapsed = int((time.monotonic() - started) * 1000)
                        self.last_trace.record_tool_call(tool_call.name, tool_call.arguments, result, elapsed)
                        if tool_call.name.startswith("get_") or tool_call.name.startswith("review") or tool_call.name.startswith("diagnose") or tool_call.name.startswith("fix"):
                            summary = _tool_result_summary(result)
                            if on_tool_end:
                                await _maybe_async(on_tool_end, tool_call.name, summary)
                        messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call.id,
                                "name": tool_call.name,
                                "content": _json_dump(result),
                            }
                        )
                    continue
                return response.content
        except Exception as exc:
            if self.last_trace:
                self.last_trace.notes.append(str(exc))
            output = await self.fallback(input_data)
            if on_chunk:
                await _maybe_async(on_chunk, output.reply)
            return output.reply

        return final_content

    def build_user_prompt(self, input_data: ConversationInput) -> str:
        history = input_data.session_messages[-8:]
        return (
            "根据上下文回答用户问题。必要时调用工具。\n\n"
            f"Context: repo_root={input_data.repo_root}, branch={input_data.git_branch}, pr_number={input_data.pr_number}\n"
            f"Cached diff present={bool(input_data.cached_diff)}, cached ci log present={bool(input_data.cached_ci_log)}\n"
            f"History: {json.dumps(history, ensure_ascii=False)}\n\n"
            f"User: {input_data.user_input}"
        )

    async def fallback(self, input_data: ConversationInput) -> ConversationOutput:
        text = input_data.user_input.lower().strip()
        if any(token in text for token in ["review", "审查", "代码质量", "看看改动", "pr"]):
            diff = input_data.cached_diff or await self._resolve_review_diff()
            if not diff:
                return ConversationOutput(reply="我没拿到可审查的 diff。先 `git diff` 产生变更，或者用 `/review`。")
            result = await self.tool_review(diff=diff, rules=find_project_rules(self.repo_root))
            return ConversationOutput(reply=ReviewOutput.model_validate(result).to_markdown())

        if any(token in text for token in ["diagnose", "ci", "构建", "失败", "日志"]):
            log = input_data.cached_ci_log or await self._resolve_ci_log()
            if not log:
                return ConversationOutput(reply="我没检测到失败的 CI 日志。你可以先用 `/diagnose --log-file ...` 或把日志贴给我。")
            result = await self.tool_diagnose(log=log)
            return ConversationOutput(reply=CICDOutput.model_validate(result).to_markdown())

        if any(token in text for token in ["fix", "修一下", "修复", "补丁"]):
            diagnosis = self._current_session.cached_review or self._current_session.cached_diagnosis
            if diagnosis is None:
                return ConversationOutput(reply="还没有可用的诊断结果。先执行 `/review` 或 `/diagnose`。")
            result = await self.tool_fix(diagnosis=diagnosis.model_dump(), file_paths=None)
            return ConversationOutput(reply=FixOutput.model_validate(result).to_markdown())

        if any(token in text for token in ["context", "上下文", "当前状态", "仓库"]):
            context = await self.tool_get_git_context()
            pr_info = await self.tool_get_pr_info(repo=None, pr_number=self._current_session.pr_number)
            ci_status = await self.tool_get_ci_status(repo=None, pr_number=self._current_session.pr_number)
            return ConversationOutput(reply=self._format_context(context, pr_info, ci_status))

        return ConversationOutput(reply="我理解到你在问项目上下文或代码问题，但意图还不够清楚。你可以直接说“帮我审查当前改动”或用 `/help`。")

    async def tool_review(self, diff: str, rules: str | None = None) -> dict:
        review_input = ReviewInput(
            diff=diff,
            changed_files=changed_files(diff),
            repo_root=str(self.repo_root),
        )
        result = await self.review_agent.run(review_input)
        self._current_session.cached_diff = diff
        self._current_session.cached_review = result
        self._current_session.last_diagnosis = result
        return result.model_dump()

    async def tool_diagnose(self, log: str) -> dict:
        result = await self.cicd_agent.run(CICDInput(log_text=log, repo_root=str(self.repo_root)))
        self._current_session.cached_ci_log = log
        self._current_session.cached_diagnosis = result
        self._current_session.last_diagnosis = result
        return result.model_dump()

    async def tool_fix(self, diagnosis: dict, file_paths: list[str] | None = None) -> dict:
        diagnosis_obj = self._coerce_diagnosis(diagnosis)
        file_contents = {}
        for path in file_paths or self._collect_file_paths(diagnosis_obj):
            target = self.repo_root / path
            if target.exists():
                file_contents[path] = target.read_text(encoding="utf-8")
        result = await self.fix_agent.run(
            FixInput(
                diagnosis=diagnosis_obj,
                file_contents=file_contents,
                repo_root=str(self.repo_root),
                rules=find_project_rules(self.repo_root),
            )
        )
        self._current_session.cached_fix = result
        return result.model_dump()

    async def tool_get_git_context(self) -> dict:
        return collect_git_context(self.repo_root).model_dump()

    async def tool_get_pr_info(self, repo: str | None = None, pr_number: int | None = None) -> dict:
        info = collect_pr_info(self.repo_root, repo=repo, pr_number=pr_number or self._current_session.pr_number)
        if info.pr_number:
            self._current_session.pr_number = info.pr_number
        return info.model_dump()

    async def tool_get_ci_status(self, repo: str | None = None, pr_number: int | None = None) -> dict:
        return collect_ci_status(self.repo_root, repo=repo, pr_number=pr_number or self._current_session.pr_number).model_dump()

    async def _resolve_review_diff(self) -> str | None:
        pr_info = collect_pr_info(self.repo_root, pr_number=self._current_session.pr_number)
        if pr_info.diff:
            return pr_info.diff
        git_context = collect_git_context(self.repo_root)
        if git_context.staged_diff:
            return git_context.staged_diff
        diff = collect_git_context(self.repo_root, staged=False).staged_diff
        return diff

    async def _resolve_ci_log(self) -> str | None:
        status = collect_ci_status(self.repo_root, pr_number=self._current_session.pr_number)
        return status.log_text

    def _coerce_diagnosis(self, diagnosis: dict | ReviewOutput | CICDOutput) -> ReviewOutput | CICDOutput:
        if isinstance(diagnosis, (ReviewOutput, CICDOutput)):
            return diagnosis
        if "issues" in diagnosis:
            return ReviewOutput.model_validate(diagnosis)
        return CICDOutput.model_validate(diagnosis)

    def _collect_file_paths(self, diagnosis: ReviewOutput | CICDOutput) -> list[str]:
        if isinstance(diagnosis, ReviewOutput):
            return sorted({issue.file for issue in diagnosis.issues if issue.file})
        return sorted({path for path in diagnosis.affected_files if path})

    def _streaming_messages(self, input_data: ConversationInput) -> list[dict[str, str]]:
        history = input_data.session_messages[-10:]
        if history:
            return [{"role": item["role"], "content": item["content"]} for item in history]
        return [{"role": "user", "content": input_data.user_input}]

    def _streaming_system_prompt(self, input_data: ConversationInput) -> str:
        context_lines = [
            f"repo_root={input_data.repo_root or 'N/A'}",
            f"branch={input_data.git_branch or 'N/A'}",
            f"pr_number={input_data.pr_number or 'N/A'}",
            f"cached_diff={bool(input_data.cached_diff)}",
            f"cached_ci_log={bool(input_data.cached_ci_log)}",
        ]
        return STREAMING_SYSTEM_PROMPT + "\n\nCurrent context:\n- " + "\n- ".join(context_lines)

    def _format_context(self, context: dict, pr_info: dict, ci_status: dict) -> str:
        lines = [
            f"- Repo: `{context.get('repo_root') or 'N/A'}`",
            f"- Branch: `{context.get('branch') or 'N/A'}`",
            f"- PR: `{pr_info.get('pr_number') or 'N/A'}` {pr_info.get('title') or ''}".rstrip(),
            f"- CI: `{ci_status.get('status', 'none')}`",
        ]
        if context.get("recent_commits"):
            lines.append("- Recent commits:")
            lines.extend(f"  - {item}" for item in context["recent_commits"][:5])
        return "\n".join(lines)


async def _maybe_async(callback, *args: Any) -> None:
    result = callback(*args)
    if hasattr(result, "__await__"):
        await result


def _tool_result_summary(result: Any) -> str:
    if isinstance(result, dict):
        if "summary" in result:
            return str(result["summary"])
        if "status" in result:
            return str(result["status"])
        if "reply" in result:
            return str(result["reply"])
    if isinstance(result, list):
        return f"{len(result)} items"
    text = str(result)
    return text[:80] + ("..." if len(text) > 80 else "")
