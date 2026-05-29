from pathlib import Path

from argus.agents.cicd import CICDAgent
from argus.agents.conversation import ConversationAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.llm import LLMResponse, StaticProvider, ToolCall
from argus.models import ConversationInput
from argus.repl.context import Session


async def test_conversation_agent_routes_review_request(tmp_path) -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")
    session = Session(repo_root=str(tmp_path), git_branch="main", cached_diff=diff)
    agent = ConversationAgent(
        review_agent=ReviewAgent(repo_root=tmp_path),
        cicd_agent=CICDAgent(repo_root=tmp_path),
        fix_agent=FixAgent(repo_root=tmp_path),
        repo_root=tmp_path,
    )

    reply = await agent.respond(
        ConversationInput(
            user_input="帮我审查当前改动",
            session_messages=[],
            repo_root=str(tmp_path),
            git_branch="main",
            cached_diff=diff,
        ),
        session,
    )

    assert "总结" in reply or "SQL" in reply
    assert session.cached_review is not None


async def test_conversation_agent_streams_chunks_and_tools(tmp_path) -> None:
    session = Session(repo_root=str(tmp_path), git_branch="main")
    provider = StaticProvider(
        responses=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="call-1", name="get_git_context", arguments={})],
                stop_reason="tool_use",
                usage={"input_tokens": 3, "output_tokens": 1},
            ),
            LLMResponse(
                content="当前仓库上下文已经拿到。",
                tool_calls=[],
                stop_reason="end_turn",
                usage={"input_tokens": 5, "output_tokens": 4},
            ),
        ]
    )
    agent = ConversationAgent(
        review_agent=ReviewAgent(repo_root=tmp_path),
        cicd_agent=CICDAgent(repo_root=tmp_path),
        fix_agent=FixAgent(repo_root=tmp_path),
        provider=provider,
        repo_root=tmp_path,
    )
    chunks: list[str] = []
    tool_starts: list[str] = []
    tool_ends: list[tuple[str, str]] = []

    reply = await agent.respond(
        ConversationInput(
            user_input="告诉我当前上下文",
            session_messages=[{"role": "user", "content": "告诉我当前上下文"}],
            repo_root=str(tmp_path),
            git_branch="main",
        ),
        session,
        stream=True,
        on_chunk=chunks.append,
        on_tool_start=tool_starts.append,
        on_tool_end=lambda name, summary="": tool_ends.append((name, summary)),
    )

    assert reply == "当前仓库上下文已经拿到。"
    assert "".join(chunks) == reply
    assert tool_starts == ["get_git_context"]
    assert tool_ends[0][0] == "get_git_context"
