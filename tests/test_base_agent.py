from pydantic import BaseModel

from argus.agents.base import BaseAgent
from argus.llm import LLMResponse, StaticProvider, ToolCall
from argus.tooling import ToolDefinition


class EchoInput(BaseModel):
    value: str


class EchoOutput(BaseModel):
    summary: str


class DummyAgent(BaseAgent[EchoInput, EchoOutput]):
    name = "dummy"
    system_prompt = "Return JSON only"

    @property
    def output_model(self) -> type[EchoOutput]:
        return EchoOutput

    def register_tools(self) -> None:
        self.tools.register(
            ToolDefinition(
                name="echo",
                description="Echo tool",
                parameters={
                    "type": "object",
                    "properties": {"text": {"type": "string"}},
                    "required": ["text"],
                },
                handler=self.echo,
            )
        )

    def build_user_prompt(self, input_data: EchoInput) -> str:
        return input_data.value

    async def fallback(self, input_data: EchoInput) -> EchoOutput:
        return EchoOutput(summary=f"fallback:{input_data.value}")

    async def echo(self, text: str) -> dict[str, str]:
        return {"text": text.upper()}


async def test_base_agent_tool_loop_records_trace() -> None:
    provider = StaticProvider(
        responses=[
            LLMResponse(
                content="",
                tool_calls=[ToolCall(id="call-1", name="echo", arguments={"text": "hello"})],
                stop_reason="tool_use",
                usage={"input_tokens": 10, "output_tokens": 2},
            ),
            LLMResponse(
                content='{"summary": "done"}',
                tool_calls=[],
                stop_reason="end_turn",
                usage={"input_tokens": 5, "output_tokens": 3},
            ),
        ]
    )
    agent = DummyAgent(provider=provider)

    output = await agent.run(EchoInput(value="hello"))

    assert output.summary == "done"
    assert agent.last_trace is not None
    assert agent.last_trace.llm_rounds == 2
    assert agent.last_trace.tool_calls[0]["name"] == "echo"
