"""Argus 的工具注册与执行。"""

from __future__ import annotations

import inspect
from dataclasses import dataclass
from typing import Any, Awaitable, Callable


ToolHandler = Callable[..., Awaitable[Any] | Any]


@dataclass
class ToolDefinition:
    """单个工具的定义。"""

    name: str
    description: str
    parameters: dict[str, Any]
    handler: ToolHandler


class ToolRegistry:
    """统一管理 Agent 暴露给 LLM 的工具。"""

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition) -> None:
        """注册工具。"""

        self._tools[tool.name] = tool

    def get_schemas(self) -> list[dict[str, Any]]:
        """返回标准化工具 schema。"""

        return [
            {
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
            }
            for tool in self._tools.values()
        ]

    async def execute(self, name: str, arguments: dict[str, Any]) -> Any:
        """执行工具。"""

        tool = self._tools[name]
        result = tool.handler(**arguments)
        if inspect.isawaitable(result):
            return await result
        return result
