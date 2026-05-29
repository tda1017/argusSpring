"""REPL 主循环。"""

from __future__ import annotations

from pathlib import Path

from prompt_toolkit import PromptSession
from prompt_toolkit.history import FileHistory

from argus.agents.cicd import CICDAgent
from argus.agents.conversation import ConversationAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.github import GitHubClient
from argus.rag import LocalRetriever
from argus.repl.commands import AgentBundle, ExitREPL, process_input
from argus.repl.context import Session, detect_context
from argus.repl.renderer import console, print_welcome


async def start_repl(repo_root: Path | None = None) -> None:
    """启动交互式 REPL。"""

    session_state = detect_context(repo_root)
    root = Path(session_state.repo_root) if session_state.repo_root else (repo_root or Path.cwd())

    github = GitHubClient()
    retriever = LocalRetriever(root)
    review_agent = ReviewAgent(repo_root=root, github_client=github, retriever=retriever)
    cicd_agent = CICDAgent(repo_root=root, github_client=github, retriever=retriever)
    fix_agent = FixAgent(repo_root=root, github_client=github, retriever=retriever)
    conversation_agent = ConversationAgent(
        review_agent=review_agent,
        cicd_agent=cicd_agent,
        fix_agent=fix_agent,
        repo_root=root,
    )

    agents = AgentBundle(
        review_agent=review_agent,
        cicd_agent=cicd_agent,
        fix_agent=fix_agent,
        conversation_agent=conversation_agent,
    )
    prompt = PromptSession(history=FileHistory(".argus_history"))

    print_welcome(session_state)
    try:
        while True:
            try:
                user_input = await prompt.prompt_async("argus> ")
            except KeyboardInterrupt:
                console.print("[yellow]已中断当前输入。[/yellow]")
                continue
            except EOFError:
                console.print("[cyan]Bye.[/cyan]")
                break

            if not user_input.strip():
                continue
            try:
                await process_input(user_input, session_state, agents)
            except ExitREPL:
                console.print("[cyan]Bye.[/cyan]")
                break
    finally:
        await github.close()

