"""项目级规则文件加载与解析。"""

from __future__ import annotations

import re
from pathlib import Path


ARGUS_FILE_NAMES = ["argus.md", ".argus/config.md", "ARGUS.md"]


def find_project_rules_path(repo_root: Path) -> Path | None:
    """按优先级查找项目规则文件。"""

    for name in ARGUS_FILE_NAMES:
        path = repo_root / name
        if path.exists() and path.is_file():
            return path
    return None


def find_project_rules(repo_root: Path) -> str | None:
    """读取项目规则内容。"""

    path = find_project_rules_path(repo_root)
    return path.read_text(encoding="utf-8") if path else None


def inject_rules_to_prompt(system_prompt: str, rules: str | None) -> str:
    """把项目规则追加到系统提示词。"""

    if not rules:
        return system_prompt
    return f"{system_prompt}\n\n## Project-Specific Rules\n\n{rules.strip()}"


def extract_markdown_section(text: str, heading: str) -> str | None:
    """按标题提取 Markdown 小节内容。"""

    pattern = re.compile(
        rf"^##\s+{re.escape(heading)}\s*$\n(?P<body>.*?)(?=^##\s+|\Z)",
        re.MULTILINE | re.DOTALL,
    )
    match = pattern.search(text)
    if not match:
        return None
    return match.group("body").strip()


def extract_provider_overrides(rules: str) -> dict[str, str]:
    """从规则文件的 Provider 小节提取覆盖项。"""

    section = extract_markdown_section(rules, "Provider 配置")
    if not section:
        return {}

    overrides: dict[str, str] = {}
    for raw_line in section.splitlines():
        line = raw_line.strip()
        match = re.match(r"^-\s*([\w-]+)\s*:\s*(.+)$", line)
        if match:
            key = match.group(1).strip().lower().replace("-", "_")
            overrides[key] = match.group(2).strip()
    return overrides


def extract_topic_rules(rules: str, topic: str) -> str:
    """从规则文件里提取和 topic 最相关的内容。"""

    normalized = topic.strip().lower()
    section_map = {
        "general": "代码规范",
        "naming": "代码规范",
        "error-handling": "代码规范",
        "testing": "审查重点",
    }
    section_name = section_map.get(normalized, "代码规范")
    section = extract_markdown_section(rules, section_name)
    return section or rules
