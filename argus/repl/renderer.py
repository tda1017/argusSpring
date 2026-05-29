"""REPL 的终端渲染。"""

from __future__ import annotations

from rich.console import Console
from rich.markdown import Markdown
from rich.panel import Panel

from argus.models import CICDOutput, FixOutput, ReviewOutput
from argus.patching import render_patch_diff
from argus.repl.context import Session


console = Console()


def print_welcome(session: Session) -> None:
    """打印欢迎信息。"""

    lines = ["🔍 Argus — Interactive Code Review Assistant"]
    repo_label = session.repo or (session.repo_root or "N/A")
    lines.append(f"📁 Repo:   {repo_label}")
    lines.append(f"🌿 Branch: {session.git_branch or 'N/A'}")
    if session.pr_number:
        suffix = f" — {session.pr_title}" if session.pr_title else ""
        lines.append(f"🔗 PR:     #{session.pr_number}{suffix}")
    else:
        lines.append("🔗 PR:     N/A")
    lines.append(f"🧾 Rules:  {'yes' if session.has_argus_rules else 'no'}")
    lines.append(f"🔴 CI:     {session.ci_status}")
    lines.append("")
    lines.append("Type naturally or use /help for commands.")
    console.print(Panel.fit("\n".join(lines), border_style="cyan"))


def print_markdown(text: str) -> None:
    """渲染 Markdown。"""

    console.print(Markdown(text))


def print_review(output: ReviewOutput) -> None:
    """渲染 Review 输出。"""

    console.print(Panel.fit(output.to_markdown(), title="🔍 Code Review", border_style="red"))


def print_cicd(output: CICDOutput) -> None:
    """渲染 CI/CD 输出。"""

    console.print(Panel.fit(output.to_markdown(), title="🔧 CI/CD Analysis", border_style="yellow"))


def print_fix(output: FixOutput) -> None:
    """渲染 Fix 输出和补丁预览。"""

    console.print(Panel.fit(output.to_markdown(), title="🩹 Fix Preview", border_style="green"))
    for patch in output.patches:
        diff = render_patch_diff(patch)
        console.print(Markdown(f"```diff\n{diff}\n```"))


def print_context(session: Session) -> None:
    """渲染上下文信息。"""

    lines = [
        f"- Repo: `{session.repo or session.repo_root or 'N/A'}`",
        f"- Branch: `{session.git_branch or 'N/A'}`",
        f"- PR: `{session.pr_number or 'N/A'}`",
        f"- CI: `{session.ci_status}`",
        f"- Rules: `{session.has_argus_rules}`",
    ]
    print_markdown("\n".join(lines))


def print_help() -> None:
    """打印帮助。"""

    print_markdown(
        "\n".join(
            [
                "- `/review`：审查当前分支或 PR 的改动",
                "- `/review --staged`：只审查暂存区",
                "- `/diagnose`：分析当前分支最近一次失败 CI",
                "- `/fix`：基于最近一次诊断结果生成补丁",
                "- `/context`：显示当前上下文",
                "- `/clear`：清空对话历史和缓存",
                "- `/help`：显示帮助",
                "- `/exit`：退出",
            ]
        )
    )


def stream_text_chunk(chunk: str) -> None:
    """流式输出一段文本。"""

    console.print(chunk, end="", markup=False, highlight=False, soft_wrap=True)


def finish_stream_line() -> None:
    """结束一条流式输出。"""

    console.print()


def start_tool_status(name: str):
    """启动工具执行状态。"""

    status = console.status(f"⏳ 正在执行 {name}...", spinner="dots")
    status.start()
    return status


def stop_tool_status(status, name: str, summary: str = "") -> None:
    """结束工具执行状态。"""

    if status is not None:
        status.stop()
    suffix = f" — {summary}" if summary else ""
    console.print(f"\n[cyan]✓ {name} 完成{suffix}[/cyan]")
