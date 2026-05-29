"""统一的 LLM Provider 抽象。"""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

from argus.config import ProviderConfig, resolve_provider_config


StreamCallback = Callable[..., Awaitable[None] | None]


@dataclass
class ToolCall:
    """标准化的工具调用请求。"""

    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class LLMResponse:
    """标准化的 LLM 响应。"""

    content: str
    tool_calls: list[ToolCall]
    stop_reason: str
    usage: dict[str, int]


class ProviderNotConfigured(RuntimeError):
    """没有可用 Provider 配置时抛出的异常。"""


class LLMProvider(ABC):
    """Agent 可见的统一 Provider 接口。"""

    model: str = ""

    @abstractmethod
    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        system: str | None = None,
    ) -> LLMResponse:
        """发送一次对话请求。"""

    async def stream_chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        system: str | None = None,
        on_text: StreamCallback | None = None,
        on_tool_call: StreamCallback | None = None,
    ) -> LLMResponse:
        """默认流式实现：退化为一次性请求。"""

        response = await self.chat(messages=messages, tools=tools, system=system)
        if on_tool_call:
            for call in response.tool_calls:
                await _maybe_call(on_tool_call, call.name)
        if on_text and response.content:
            await _maybe_call(on_text, response.content)
        return response


class StaticProvider(LLMProvider):
    """测试用 Provider，按顺序返回预设响应。"""

    def __init__(self, responses: list[LLMResponse]):
        self._responses = list(responses)
        self.model = "static"

    async def chat(self, messages: list[dict[str, Any]], tools=None, system=None) -> LLMResponse:
        if not self._responses:
            raise RuntimeError("No more static responses configured")
        return self._responses.pop(0)

    async def stream_chat(self, messages, tools=None, system=None, on_text=None, on_tool_call=None) -> LLMResponse:
        response = await self.chat(messages=messages, tools=tools, system=system)
        if on_tool_call:
            for call in response.tool_calls:
                await _maybe_call(on_tool_call, call.name)
        if on_text and response.content:
            for chunk in _split_stream_text(response.content):
                await _maybe_call(on_text, chunk)
        return response


class AnthropicProvider(LLMProvider):
    """Anthropic SDK 的标准封装。"""

    def __init__(self, api_key: str, base_url: str, model: str):
        try:
            from anthropic import AsyncAnthropic
        except ImportError as exc:
            raise RuntimeError("anthropic package is not installed") from exc

        kwargs = {"api_key": api_key}
        if base_url:
            kwargs["base_url"] = base_url
        self.client = AsyncAnthropic(**kwargs)
        self.model = model

    async def chat(self, messages, tools=None, system=None) -> LLMResponse:
        payload = self._payload(messages=messages, tools=tools, system=system)
        response = await self.client.messages.create(**payload)
        return self._normalize_message(response)

    async def stream_chat(self, messages, tools=None, system=None, on_text=None, on_tool_call=None) -> LLMResponse:
        payload = self._payload(messages=messages, tools=tools, system=system)
        async with self.client.messages.stream(**payload) as stream:
            async for event in stream:
                if event.type == "text" and on_text:
                    await _maybe_call(on_text, event.text)
                elif (
                    event.type == "content_block_start"
                    and getattr(event.content_block, "type", "") == "tool_use"
                    and on_tool_call
                ):
                    await _maybe_call(on_tool_call, event.content_block.name)
            final_message = await stream.get_final_message()
        return self._normalize_message(final_message)

    def _payload(self, messages, tools=None, system=None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": self._convert_messages(messages),
            "max_tokens": 4096,
        }
        if system:
            payload["system"] = system
        if tools:
            payload["tools"] = [
                {
                    "name": tool["name"],
                    "description": tool["description"],
                    "input_schema": tool["parameters"],
                }
                for tool in tools
            ]
        return payload

    def _normalize_message(self, response) -> LLMResponse:
        tool_calls: list[ToolCall] = []
        text_parts: list[str] = []
        for block in response.content:
            if getattr(block, "type", "") == "text":
                text_parts.append(block.text)
            elif getattr(block, "type", "") == "tool_use":
                tool_calls.append(ToolCall(id=block.id, name=block.name, arguments=dict(block.input)))

        stop_reason = "tool_use" if tool_calls else "end_turn"
        usage = {
            "input_tokens": getattr(response.usage, "input_tokens", 0),
            "output_tokens": getattr(response.usage, "output_tokens", 0),
        }
        return LLMResponse(
            content="\n".join(text_parts).strip(),
            tool_calls=tool_calls,
            stop_reason=stop_reason,
            usage=usage,
        )

    def _convert_messages(self, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        converted: list[dict[str, Any]] = []
        for message in messages:
            role = message["role"]
            if role == "tool":
                converted.append(
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": message["tool_call_id"],
                                "content": message["content"],
                            }
                        ],
                    }
                )
                continue

            if role == "assistant" and message.get("tool_calls"):
                content: list[dict[str, Any]] = []
                if message.get("content"):
                    content.append({"type": "text", "text": message["content"]})
                for call in message["tool_calls"]:
                    content.append(
                        {
                            "type": "tool_use",
                            "id": call["id"],
                            "name": call["name"],
                            "input": call["arguments"],
                        }
                    )
                converted.append({"role": "assistant", "content": content})
                continue

            converted.append({"role": role, "content": message.get("content", "")})
        return converted


class OpenAICompatibleProvider(LLMProvider):
    """OpenAI 兼容格式 Provider 封装。"""

    def __init__(self, api_key: str, base_url: str, model: str):
        try:
            from openai import AsyncOpenAI
        except ImportError as exc:
            raise RuntimeError("openai package is not installed") from exc

        kwargs = {"api_key": api_key}
        if base_url:
            kwargs["base_url"] = base_url
        self.client = AsyncOpenAI(**kwargs)
        self.model = model

    async def chat(self, messages, tools=None, system=None) -> LLMResponse:
        kwargs = self._payload(messages=messages, tools=tools, system=system)
        response = await self.client.chat.completions.create(**kwargs)
        return self._normalize_response(response)

    async def stream_chat(self, messages, tools=None, system=None, on_text=None, on_tool_call=None) -> LLMResponse:
        kwargs = self._payload(messages=messages, tools=tools, system=system)
        kwargs["stream"] = True
        stream = await self.client.chat.completions.create(**kwargs)

        text_parts: list[str] = []
        buffers: dict[int, dict[str, Any]] = {}
        usage = {"input_tokens": 0, "output_tokens": 0}

        async for chunk in stream:
            if getattr(chunk, "usage", None):
                usage = {
                    "input_tokens": getattr(chunk.usage, "prompt_tokens", 0) or 0,
                    "output_tokens": getattr(chunk.usage, "completion_tokens", 0) or 0,
                }
            for choice in chunk.choices:
                delta = choice.delta
                if delta.content:
                    text_parts.append(delta.content)
                    if on_text:
                        await _maybe_call(on_text, delta.content)
                for tool_delta in delta.tool_calls or []:
                    entry = buffers.setdefault(
                        tool_delta.index,
                        {"id": tool_delta.id or f"tool-{tool_delta.index}", "name": "", "arguments": []},
                    )
                    if tool_delta.id:
                        entry["id"] = tool_delta.id
                    function = getattr(tool_delta, "function", None)
                    if function and function.name:
                        if not entry["name"] and on_tool_call:
                            await _maybe_call(on_tool_call, function.name)
                        entry["name"] = function.name
                    if function and function.arguments:
                        entry["arguments"].append(function.arguments)

        tool_calls = [
            ToolCall(
                id=item["id"],
                name=item["name"],
                arguments=_safe_json_load("".join(item["arguments"])),
            )
            for _, item in sorted(buffers.items())
            if item["name"]
        ]
        return LLMResponse(
            content="".join(text_parts).strip(),
            tool_calls=tool_calls,
            stop_reason="tool_use" if tool_calls else "end_turn",
            usage=usage,
        )

    def _payload(self, messages, tools=None, system=None) -> dict[str, Any]:
        payload_messages = []
        if system:
            payload_messages.append({"role": "system", "content": system})
        payload_messages.extend(messages)

        kwargs: dict[str, Any] = {"model": self.model, "messages": payload_messages}
        if tools:
            kwargs["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": tool["name"],
                        "description": tool["description"],
                        "parameters": tool["parameters"],
                    },
                }
                for tool in tools
            ]
        return kwargs

    def _normalize_response(self, response) -> LLMResponse:
        choice = response.choices[0]
        message = choice.message
        tool_calls: list[ToolCall] = []
        for call in message.tool_calls or []:
            tool_calls.append(
                ToolCall(
                    id=call.id,
                    name=call.function.name,
                    arguments=json.loads(call.function.arguments or "{}"),
                )
            )

        usage = {
            "input_tokens": getattr(response.usage, "prompt_tokens", 0) or 0,
            "output_tokens": getattr(response.usage, "completion_tokens", 0) or 0,
        }
        stop_reason = "tool_use" if tool_calls else "end_turn"
        return LLMResponse(content=message.content or "", tool_calls=tool_calls, stop_reason=stop_reason, usage=usage)


def create_provider(config: ProviderConfig) -> LLMProvider:
    """根据配置实例化 Provider。"""

    if config.api_type == "anthropic":
        return AnthropicProvider(api_key=config.api_key, base_url=config.base_url, model=config.model)
    if config.api_type in {"openai", "openai-compatible"}:
        return OpenAICompatibleProvider(api_key=config.api_key, base_url=config.base_url, model=config.model)
    raise ValueError(f"Unknown provider type: {config.api_type}")


def load_provider(repo_root: Path | None = None, allow_missing: bool = True) -> LLMProvider | None:
    """按优先级加载 Provider。"""

    config = resolve_provider_config(repo_root)
    if not config:
        if allow_missing:
            return None
        raise ProviderNotConfigured("No provider configured")
    return create_provider(config)


async def _maybe_call(callback: StreamCallback, *args: Any) -> None:
    result = callback(*args)
    if hasattr(result, "__await__"):
        await result


def _split_stream_text(text: str, chunk_size: int = 16) -> list[str]:
    return [text[index : index + chunk_size] for index in range(0, len(text), chunk_size)] or [""]


def _safe_json_load(raw: str) -> dict[str, Any]:
    try:
        return json.loads(raw or "{}")
    except json.JSONDecodeError:
        return {}
