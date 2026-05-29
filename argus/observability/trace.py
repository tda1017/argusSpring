"""执行 trace 记录。"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from argus.observability.cost import estimate_cost


@dataclass
class AgentTrace:
    """记录 Agent 一次执行的关键路径。"""

    agent: str
    event_type: str = ""
    repo: str = ""
    pr_number: int | None = None
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4())[:8])
    started_at: float = field(default_factory=time.monotonic)
    llm_rounds: int = 0
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    rag_queries: list[dict[str, Any]] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def record_tool_call(self, name: str, args: dict[str, Any], result: Any, duration_ms: int) -> None:
        """记录一次工具调用。"""

        preview = str(result)
        if len(preview) > 120:
            preview = preview[:117] + "..."
        self.tool_calls.append(
            {
                "name": name,
                "args": args,
                "result_preview": preview,
                "duration_ms": duration_ms,
            }
        )

    def record_rag_query(self, query: str, results: list[Any]) -> None:
        """记录一次检索。"""

        self.rag_queries.append(
            {
                "query": query,
                "result_count": len(results),
                "files": [getattr(item, "file", "") for item in results[:5]],
            }
        )

    def add_usage(self, input_tokens: int, output_tokens: int) -> None:
        """累计 token 用量。"""

        self.total_input_tokens += input_tokens
        self.total_output_tokens += output_tokens

    def duration_ms(self) -> int:
        """返回当前 trace 持续时间。"""

        return int((time.monotonic() - self.started_at) * 1000)

    def to_summary(self, model: str = "") -> str:
        """生成 CLI 友好的 trace 摘要。"""

        lines = [f"[Trace] ID: {self.trace_id}", f"[Trace] LLM rounds: {self.llm_rounds}"]
        if self.tool_calls:
            lines.append("[Trace] Tool calls:")
            for index, call in enumerate(self.tool_calls, start=1):
                lines.append(
                    f"  {index}. {call['name']}({call['args']}) -> {call['result_preview']} ({call['duration_ms']}ms)"
                )
        lines.append(f"[Trace] Tokens: {self.total_input_tokens} in / {self.total_output_tokens} out")
        if model:
            lines.append(f"[Trace] Cost: ${estimate_cost(model, self.total_input_tokens, self.total_output_tokens):.6f}")
        lines.append(f"[Trace] Total time: {self.duration_ms() / 1000:.2f}s")
        return "\n".join(lines)

    def to_markdown(self, model: str = "") -> str:
        """生成 GitHub comment 里的 details 块。"""

        lines = [f"<details>", f"<summary>🔬 Debug info (trace: {self.trace_id})</summary>", ""]
        lines.append(f"- Agent: {self.agent}")
        lines.append(f"- LLM rounds: {self.llm_rounds}")
        if self.tool_calls:
            lines.append(f"- Tools called: {', '.join(call['name'] for call in self.tool_calls)}")
        lines.append(f"- Tokens: {self.total_input_tokens} in / {self.total_output_tokens} out")
        if model:
            lines.append(f"- Estimated cost: ${estimate_cost(model, self.total_input_tokens, self.total_output_tokens):.6f}")
        lines.append(f"- Duration: {self.duration_ms() / 1000:.2f}s")
        if self.rag_queries:
            lines.append(f"- RAG hits: {sum(item['result_count'] for item in self.rag_queries)}")
        lines.extend(["", "</details>"])
        return "\n".join(lines)
