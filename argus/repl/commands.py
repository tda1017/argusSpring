"""REPL 斜杠命令。"""

from __future__ import annotations

import shlex
from dataclasses import dataclass
from pathlib import Path

from rich.prompt import Confirm

from argus.agents.cicd import CICDAgent
from argus.agents.conversation import ConversationAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.models import CICDInput, ConversationInput, FixInput, ReviewInput
from argus.patching import apply_file_patches
from argus.project_rules import find_project_rules
from argus.repl.context import Session, collect_ci_status, get_review_diff
from argus.repl.renderer import (
    console,
    finish_stream_line,
    print_cicd,
    print_context,
    print_fix,
    print_help,
    print_review,
    start_tool_status,
    stop_tool_status,
    stream_text_chunk,
)
from argus.tools.diff_parser import changed_files


class ExitREPL(Exception):
    """用于退出 REPL 的内部异常。"""


@dataclass
class AgentBundle:
    """REPL 使用的 Agent 集合。"""

    review_agent: ReviewAgent
    cicd_agent: CICDAgent
    fix_agent: FixAgent
    conversation_agent: ConversationAgent


async def handle_review(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/review`。"""

    args = shlex.split(user_input)
    staged = "--staged" in args
    diff = get_review_diff(session, staged=staged)
    if not diff:
        console.print("[yellow]没拿到 diff。先改点代码，或者把变更放进暂存区。[/yellow]")
        return

    output = await agents.review_agent.run(
        ReviewInput(
            repo=session.repo,
            pr_number=session.pr_number,
            diff=diff,
            changed_files=changed_files(diff),
            pr_title=session.pr_title or "",
            repo_root=session.repo_root,
        )
    )
    session.cached_diff = diff
    session.cached_review = output
    session.last_diagnosis = output
    print_review(output)


async def handle_diagnose(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/diagnose`。"""

    args = shlex.split(user_input)
    log_file = None
    if "--log-file" in args:
        index = args.index("--log-file")
        if index + 1 < len(args):
            log_file = Path(args[index + 1])

    if log_file:
        log_text = log_file.read_text(encoding="utf-8")
    else:
        status = collect_ci_status(Path(session.repo_root) if session.repo_root else Path.cwd(), repo=session.repo, pr_number=session.pr_number)
        log_text = status.log_text
        session.ci_status = status.status

    if not log_text:
        console.print("[yellow]没检测到失败 CI 日志。可以用 `/diagnose --log-file build.log`。[/yellow]")
        return

    output = await agents.cicd_agent.run(CICDInput(repo=session.repo, log_text=log_text, repo_root=session.repo_root))
    session.cached_ci_log = log_text
    session.cached_diagnosis = output
    session.last_diagnosis = output
    print_cicd(output)


async def handle_fix(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/fix`。"""

    diagnosis = session.last_diagnosis or session.cached_review or session.cached_diagnosis
    if diagnosis is None:
        console.print("[yellow]还没有可用诊断结果。先运行 `/review` 或 `/diagnose`。[/yellow]")
        return

    file_paths = _collect_paths(diagnosis)
    file_contents = {}
    repo_root = Path(session.repo_root or Path.cwd())
    for path in file_paths:
        target = repo_root / path
        if target.exists():
            file_contents[path] = target.read_text(encoding="utf-8")

    output = await agents.fix_agent.run(
        FixInput(
            diagnosis=diagnosis,
            file_contents=file_contents,
            repo_root=str(repo_root),
            rules=find_project_rules(repo_root),
        )
    )
    session.cached_fix = output
    print_fix(output)

    if not output.patches:
        return

    if Confirm.ask("是否应用这些补丁？", default=False):
        result = apply_file_patches(repo_root, output.patches, dry_run=False)
        if result.applied:
            console.print(f"[green]已应用: {', '.join(result.applied)}[/green]")
        if result.failed:
            console.print(f"[red]失败: {result.failed}[/red]")


async def handle_context(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/context`。"""

    print_context(session)


async def handle_clear(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/clear`。"""

    session.clear()
    console.print("[green]已清空对话历史和缓存。[/green]")


async def handle_help(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/help`。"""

    print_help()


async def handle_exit(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理 `/exit`。"""

    raise ExitREPL()


SLASH_COMMANDS = {
    "/review": handle_review,
    "/diagnose": handle_diagnose,
    "/fix": handle_fix,
    "/context": handle_context,
    "/clear": handle_clear,
    "/help": handle_help,
    "/exit": handle_exit,
}


async def process_input(user_input: str, session: Session, agents: AgentBundle) -> None:
    """处理一条 REPL 输入。"""

    command = user_input.strip().split()[0]
    if command in SLASH_COMMANDS:
        await SLASH_COMMANDS[command](user_input, session, agents)
        return

    session.add_message("user", user_input)
    active_status = None
    emitted_text = False

    async def on_chunk(chunk: str) -> None:
        nonlocal active_status, emitted_text
        if active_status is not None:
            stop_tool_status(active_status, "tool")
            active_status = None
        stream_text_chunk(chunk)
        emitted_text = True

    async def on_tool_start(name: str) -> None:
        nonlocal active_status, emitted_text
        if emitted_text:
            finish_stream_line()
            emitted_text = False
        if active_status is not None:
            active_status.stop()
        active_status = start_tool_status(name)

    async def on_tool_end(name: str, summary: str = "") -> None:
        nonlocal active_status
        if active_status is not None:
            stop_tool_status(active_status, name, summary)
            active_status = None

    reply = await agents.conversation_agent.respond(
        ConversationInput(
            user_input=user_input,
            session_messages=session.messages,
            repo_root=session.repo_root,
            git_branch=session.git_branch,
            pr_number=session.pr_number,
            cached_diff=session.cached_diff,
            cached_ci_log=session.cached_ci_log,
        ),
        session,
        stream=True,
        on_chunk=on_chunk,
        on_tool_start=on_tool_start,
        on_tool_end=on_tool_end,
    )
    if active_status is not None:
        active_status.stop()
    if emitted_text:
        finish_stream_line()
    session.add_message("assistant", reply)


def _collect_paths(diagnosis) -> list[str]:
    if hasattr(diagnosis, "issues"):
        return sorted({issue.file for issue in diagnosis.issues if issue.file})
    return sorted({path for path in diagnosis.affected_files if path})
