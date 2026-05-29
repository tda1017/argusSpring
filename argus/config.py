"""运行时配置与 Provider 配置加载。"""

from __future__ import annotations

import json
import os
from pathlib import Path

from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from argus.project_rules import extract_provider_overrides, find_project_rules


class Settings(BaseSettings):
    """环境变量配置。"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    argus_provider: str = ""
    argus_api_key: str = ""
    argus_base_url: str = ""
    argus_model: str = ""
    argus_index_dir: str = ".argus/index"
    argus_auto_fix: bool = False
    argus_fix_confidence_threshold: float = 0.8
    github_app_id: int = 0
    github_private_key: str = ""
    github_webhook_secret: str = ""
    github_token: str = ""


class ProviderConfig(BaseModel):
    """统一的 Provider 配置。"""

    provider: str
    api_type: str
    api_key: str = ""
    base_url: str = ""
    model: str
    metadata: dict[str, str] = Field(default_factory=dict)

    @classmethod
    def from_env(cls, settings: Settings) -> "ProviderConfig | None":
        """从环境变量构建配置。"""

        if not settings.argus_provider:
            return None

        provider = settings.argus_provider.lower()
        api_type = "anthropic" if provider == "anthropic" else "openai-compatible"
        return cls(
            provider=provider,
            api_type=api_type,
            api_key=settings.argus_api_key,
            base_url=settings.argus_base_url,
            model=settings.argus_model or _default_model_for_provider(provider),
        )


def _default_model_for_provider(provider: str) -> str:
    """给常见 Provider 一个保守默认值。"""

    if provider == "anthropic":
        return "claude-sonnet-4-20250514"
    return "gpt-4o-mini"


def load_settings() -> Settings:
    """读取环境配置。"""

    return Settings()


def global_config_path() -> Path:
    """返回全局配置路径。"""

    return Path("~/.argus/config.json").expanduser()


def load_global_provider_config(path: Path | None = None) -> ProviderConfig | None:
    """读取全局 Provider 配置。"""

    config_path = path or global_config_path()
    if not config_path.exists():
        return None

    payload = json.loads(config_path.read_text(encoding="utf-8"))
    providers = payload.get("providers", {})
    active_provider = payload.get("active_provider")
    if not active_provider or active_provider not in providers:
        return None

    provider_config = providers[active_provider]
    models = provider_config.get("models", {})
    return ProviderConfig(
        provider=active_provider,
        api_type=provider_config.get("api_type", "openai-compatible"),
        api_key=provider_config.get("api_key", ""),
        base_url=provider_config.get("base_url", ""),
        model=models.get("default") or _default_model_for_provider(active_provider),
        metadata={
            key: str(value)
            for key, value in provider_config.items()
            if key not in {"api_type", "api_key", "base_url", "models"}
        },
    )


def load_project_provider_config(repo_root: Path) -> ProviderConfig | None:
    """从项目规则文件读取 Provider 覆盖项。"""

    rules = find_project_rules(repo_root)
    if not rules:
        return None

    overrides = extract_provider_overrides(rules)
    provider = overrides.get("provider")
    if not provider:
        return None

    provider = provider.lower()
    api_type = overrides.get("api_type") or ("anthropic" if provider == "anthropic" else "openai-compatible")
    return ProviderConfig(
        provider=provider,
        api_type=api_type,
        api_key=overrides.get("api_key", ""),
        base_url=overrides.get("base_url", ""),
        model=overrides.get("model") or _default_model_for_provider(provider),
        metadata={key: value for key, value in overrides.items() if key not in {"provider", "api_type", "api_key", "base_url", "model"}},
    )


def merge_provider_configs(base: ProviderConfig, override: ProviderConfig) -> ProviderConfig:
    """用 override 覆盖 base 的非空字段。"""

    payload = base.model_dump()
    for key, value in override.model_dump().items():
        if value not in ("", {}, None):
            payload[key] = value
    return ProviderConfig(**payload)


def resolve_provider_config(repo_root: Path | None = None) -> ProviderConfig | None:
    """按优先级解析 Provider 配置。"""

    settings = load_settings()
    root = repo_root or Path.cwd()

    global_config = load_global_provider_config()
    project_config = load_project_provider_config(root)
    env_config = ProviderConfig.from_env(settings)

    config = global_config
    if config and project_config:
        config = merge_provider_configs(config, project_config)
    elif project_config:
        config = project_config

    if config and env_config:
        config = merge_provider_configs(config, env_config)
    elif env_config:
        config = env_config

    return config


def save_global_provider_config(config: ProviderConfig, path: Path | None = None) -> Path:
    """保存全局 Provider 配置。"""

    config_path = path or global_config_path()
    config_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "providers": {
            config.provider: {
                "api_key": config.api_key,
                "base_url": config.base_url,
                "api_type": config.api_type,
                "models": {"default": config.model},
                **config.metadata,
            }
        },
        "active_provider": config.provider,
    }
    config_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return config_path


def environment_provider_payload() -> dict[str, str]:
    """给 CLI 展示当前环境覆盖。"""

    return {
        key: value
        for key, value in {
            "ARGUS_PROVIDER": os.getenv("ARGUS_PROVIDER", ""),
            "ARGUS_MODEL": os.getenv("ARGUS_MODEL", ""),
            "ARGUS_BASE_URL": os.getenv("ARGUS_BASE_URL", ""),
        }.items()
        if value
    }
