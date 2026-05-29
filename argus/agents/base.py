"""Agent 基类与通用 tool-calling loop。"""

from __future__ import annotations

import json
import time
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Generic, TypeVar

from pydantic import BaseModel

from argus.llm import LLMProvider, load_provider
from argus.observability.trace import AgentTrace
from argus.project_rules import find_project_rules, inject_rules_to_prompt
from argus.tooling import ToolRegistry


InputModelT = TypeVar("InputModelT", bound=BaseModel)
OutputModelT = TypeVar("OutputModelT", bound=BaseModel)


class BaseAgent(ABC, Generic[InputModelT, OutputModelT]):
    """所有 Agent 共享的核心控制流。"""

    name = "base"
    system_prompt = ""
    max_rounds = 10

    def __init__(self, provider: LLMProvider | None = None, repo_root: Path | None = None):
        self.repo_root = (repo_root or Path.cwd()).resolve()
        self.provider = provider or load_provider(self.repo_root, allow_missing=True)
        self.tools = ToolRegistry()
        self.last_trace: AgentTrace | None = None
        self.model_name = getattr(self.provider, "model", "") if self.provider else ""
        self.register_tools()

    @property
    @abstractmethod
    def output_model(self) -> type[OutputModelT]:
        """返回该 Agent 的输出模型类型。"""

    @abstractmethod
    def register_tools(self) -> None:
        """注册 Agent 需要的工具。"""

    @abstractmethod
    def build_user_prompt(self, input_data: InputModelT) -> str:
        """把结构化输入转成用户消息。"""

    @abstractmethod
    async def fallback(self, input_data: InputModelT) -> OutputModelT:
        """没有 Provider 或调用失败时的兜底逻辑。"""

    async def run(self, input_data: InputModelT) -> OutputModelT:
        """执行 Agent 主流程。"""

        self.last_trace = self.create_trace(input_data)
        if not self.provider:
            return await self.postprocess(await self.fallback(input_data), input_data)

        try:
            rules = find_project_rules(self.repo_root)
            system = inject_rules_to_prompt(self.system_prompt, rules)
            messages: list[dict[str, Any]] = [{"role": "user", "content": self.build_user_prompt(input_data)}]

            for _ in range(self.max_rounds):
                self.last_trace.llm_rounds += 1
                response = await self.provider.chat(
                    messages=messages,
                    tools=self.tools.get_schemas() or None,
                    system=system,
                )
                self.last_trace.add_usage(
                    response.usage.get("input_tokens", 0),
                    response.usage.get("output_tokens", 0),
                )
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
                        if tool_call.name.startswith("search_"):
                            results = result if isinstance(result, list) else []
                            self.last_trace.record_rag_query(tool_call.arguments.get("query") or tool_call.arguments.get("error_message", ""), results)
                        messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call.id,
                                "name": tool_call.name,
                                "content": _json_dump(result),
                            }
                        )
                    continue

                messages.append({"role": "assistant", "content": response.content})
                parsed = self.output_model.model_validate(_extract_json(response.content))
                return await self.postprocess(parsed, input_data)
        except Exception as exc:
            if self.last_trace:
                self.last_trace.notes.append(str(exc))
            return await self.postprocess(await self.fallback(input_data), input_data)

        raise RuntimeError(f"{self.name} exceeded max tool-calling rounds")

    async def postprocess(self, output: OutputModelT, input_data: InputModelT) -> OutputModelT:
        """子类可重写后处理。"""

        return output

    def create_trace(self, input_data: InputModelT) -> AgentTrace:
        """为一次执行创建 trace。"""

        payload = input_data.model_dump()
        return AgentTrace(
            agent=self.name,
            event_type=self.name,
            repo=payload.get("repo", "") or "",
            pr_number=payload.get("pr_number"),
        )


def _extract_json(text: str) -> Any:
    """从模型输出中抽取 JSON。"""

    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = stripped.strip("`")
        if stripped.startswith("json"):
            stripped = stripped[4:].strip()
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        start = stripped.find("{")
        end = stripped.rfind("}")
        if start == -1 or end == -1:
            raise
        return json.loads(stripped[start : end + 1])


def _json_dump(value: Any) -> str:
    """把工具结果序列化成 JSON 字符串。"""

    if isinstance(value, BaseModel):
        return value.model_dump_json(ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False, default=str)
