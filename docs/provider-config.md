# LLM Provider 与中转 API 配置

## 设计目标

支持多种 LLM Provider，包括官方 API 和中转服务（如 Packycode），通过统一配置切换。

## 配置文件

### 全局配置

路径: `~/.argus/config.json`

```json
{
  "providers": {
    "anthropic": {
      "api_key": "sk-ant-...",
      "base_url": "https://api.anthropic.com",
      "models": {
        "default": "claude-sonnet-4-20250514",
        "powerful": "claude-opus-4-20250514"
      }
    },
    "openai": {
      "api_key": "sk-...",
      "base_url": "https://api.openai.com/v1",
      "models": {
        "default": "gpt-4o",
        "powerful": "gpt-4o"
      }
    },
    "packycode": {
      "api_key": "sk-xxx",
      "base_url": "https://codex-api.packycode.com/v1",
      "api_type": "openai",
      "models": {
        "default": "gpt-4o",
        "powerful": "gpt-5.1"
      }
    },
    "custom": {
      "api_key": "your-key",
      "base_url": "https://your-relay.com/v1",
      "api_type": "openai",
      "models": {
        "default": "your-model"
      }
    }
  },
  "active_provider": "packycode",
  "embedding": {
    "provider": "local",
    "model": "BAAI/bge-m3"
  }
}
```

### 项目级覆盖

在 `argus.md` 中可以覆盖 provider:

```markdown
## Provider 配置
- provider: packycode
- model: gpt-4o
```

### 环境变量覆盖（最高优先级）

```bash
ARGUS_PROVIDER=anthropic
ARGUS_API_KEY=sk-ant-...
ARGUS_BASE_URL=https://api.anthropic.com
ARGUS_MODEL=claude-sonnet-4-20250514
```

## 优先级

```
环境变量 > 项目 argus.md > 全局 ~/.argus/config.json > 默认值
```

## Provider 抽象层实现

关键设计：不管底层是 Anthropic 还是 OpenAI 还是中转，Agent 层看到的接口是统一的。

```python
# src/llm/provider.py
from abc import ABC, abstractmethod

class LLMProvider(ABC):
    """统一的 LLM 调用接口"""

    @abstractmethod
    async def chat(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        system: str | None = None,
    ) -> LLMResponse:
        ...

class AnthropicProvider(LLMProvider):
    def __init__(self, api_key: str, base_url: str, model: str):
        import anthropic
        self.client = anthropic.AsyncAnthropic(
            api_key=api_key,
            base_url=base_url,
        )
        self.model = model

    async def chat(self, messages, tools=None, system=None) -> LLMResponse:
        kwargs = {
            "model": self.model,
            "messages": messages,
            "max_tokens": 4096,
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = self._convert_tools(tools)

        response = await self.client.messages.create(**kwargs)
        return self._normalize_response(response)

class OpenAICompatibleProvider(LLMProvider):
    """
    兼容所有 OpenAI 格式的 Provider:
    - OpenAI 官方
    - Packycode 中转
    - 任何 OpenAI 兼容的服务
    """
    def __init__(self, api_key: str, base_url: str, model: str):
        from openai import AsyncOpenAI
        self.client = AsyncOpenAI(
            api_key=api_key,
            base_url=base_url,
        )
        self.model = model

    async def chat(self, messages, tools=None, system=None) -> LLMResponse:
        # OpenAI 格式: system 作为第一条 message
        full_messages = []
        if system:
            full_messages.append({"role": "system", "content": system})
        full_messages.extend(messages)

        kwargs = {
            "model": self.model,
            "messages": full_messages,
        }
        if tools:
            kwargs["tools"] = self._convert_tools(tools)

        response = await self.client.chat.completions.create(**kwargs)
        return self._normalize_response(response)

# 统一响应格式
@dataclass
class LLMResponse:
    content: str                           # 文本回复
    tool_calls: list[ToolCall] | None      # 工具调用请求
    stop_reason: str                       # "end_turn" | "tool_use"
    usage: dict                            # {"input_tokens": N, "output_tokens": M}

@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict
```

## Provider 工厂

```python
# src/llm/factory.py
def create_provider(config: ProviderConfig) -> LLMProvider:
    """根据配置创建对应的 Provider 实例"""

    if config.api_type == "anthropic":
        return AnthropicProvider(
            api_key=config.api_key,
            base_url=config.base_url,
            model=config.model,
        )
    elif config.api_type in ("openai", "openai-compatible"):
        return OpenAICompatibleProvider(
            api_key=config.api_key,
            base_url=config.base_url,
            model=config.model,
        )
    else:
        raise ValueError(f"Unknown provider type: {config.api_type}")

def load_provider() -> LLMProvider:
    """按优先级加载 provider 配置并创建实例"""
    # 1. 检查环境变量
    if os.getenv("ARGUS_PROVIDER"):
        config = ProviderConfig.from_env()
    # 2. 检查项目 argus.md
    elif (rules := find_project_rules(Path.cwd())):
        config = ProviderConfig.from_rules(rules)
    # 3. 检查全局配置
    elif Path("~/.argus/config.json").expanduser().exists():
        config = ProviderConfig.from_global_config()
    else:
        raise RuntimeError("No provider configured. Run `argus init` or set ARGUS_* env vars.")

    return create_provider(config)
```

## 配置初始化 CLI

```bash
# 交互式配置
$ argus config

? Select LLM provider:
  > Anthropic (Claude)
    OpenAI
    Packycode (中转)
    Custom (OpenAI-compatible)

? Enter API Key: sk-xxx
? Enter Base URL (press Enter for default): https://codex-api.packycode.com/v1
? Select default model: gpt-4o

✅ Configuration saved to ~/.argus/config.json
```

## 使用 Packycode 中转的注意事项

Packycode 等中转服务使用 OpenAI 兼容格式，但有几个需要注意的地方:

1. **tool calling 支持**: 确认中转服务是否透传 function calling。如果不支持，需要 fallback 到 prompt-based tool calling。
2. **streaming**: 确认 SSE streaming 是否正常工作。
3. **模型名**: 中转服务的模型名可能和官方不同（如 `gpt-5.1`），需要在配置中指定。
4. **超时**: 中转服务可能比官方 API 慢，考虑增加超时时间。

```python
# 中转服务的 fallback 策略
class OpenAICompatibleProvider(LLMProvider):
    async def chat(self, messages, tools=None, system=None) -> LLMResponse:
        try:
            # 先尝试正常的 tool calling
            return await self._chat_with_tools(messages, tools, system)
        except Exception as e:
            if tools and "tool" in str(e).lower():
                # 如果中转不支持 tool calling，fallback 到 prompt 方式
                return await self._chat_with_prompt_tools(messages, tools, system)
            raise
```
