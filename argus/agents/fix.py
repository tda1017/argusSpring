"""Fix Agent 实现。"""

from __future__ import annotations

import json
import re
from pathlib import Path

from argus.agents.base import BaseAgent
from argus.github import GitHubClient, read_local_file
from argus.models import CICDOutput, FilePatch, FixInput, FixOutput, ReviewOutput
from argus.rag import LocalRetriever
from argus.tooling import ToolDefinition


FIX_SYSTEM_PROMPT = """You are Argus Fix Agent. Generate the smallest safe patch that fixes the diagnosed issue.
Return valid JSON only.
Rules:
- Prefer minimal changes.
- Do not rewrite unrelated code.
- If a fix is uncertain, mark it as unfixable.
"""


class FixAgent(BaseAgent[FixInput, FixOutput]):
    """根据诊断结果生成修复补丁。"""

    name = "fix"
    system_prompt = FIX_SYSTEM_PROMPT

    def __init__(
        self,
        provider=None,
        repo_root: Path | None = None,
        github_client: GitHubClient | None = None,
        retriever: LocalRetriever | None = None,
    ):
        self.github = github_client
        self.retriever = retriever
        super().__init__(provider=provider, repo_root=repo_root)

    @property
    def output_model(self) -> type[FixOutput]:
        return FixOutput

    def register_tools(self) -> None:
        self.tools.register(
            ToolDefinition(
                name="get_file_content",
                description="获取仓库中指定文件的完整内容",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": "string"},
                        "path": {"type": "string"},
                        "ref": {"type": "string"},
                    },
                    "required": ["path"],
                },
                handler=self.get_file_content,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="search_codebase",
                description="语义搜索项目代码库，理解相关模式和约定",
                parameters={
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "top_k": {"type": "integer", "default": 5},
                    },
                    "required": ["query"],
                },
                handler=self.search_codebase,
            )
        )

    def build_user_prompt(self, input_data: FixInput) -> str:
        schema = FixOutput.model_json_schema()
        return (
            f"Generate a fix patch and return JSON matching this schema:\n{json.dumps(schema, ensure_ascii=False)}\n\n"
            f"Diagnosis:\n{input_data.diagnosis.model_dump_json(indent=2, ensure_ascii=False)}\n\n"
            f"File contents:\n{json.dumps(input_data.file_contents, ensure_ascii=False, indent=2)}"
        )

    async def fallback(self, input_data: FixInput) -> FixOutput:
        diagnosis = input_data.diagnosis
        file_contents = dict(input_data.file_contents)
        patches: list[FilePatch] = []
        unfixable: list[str] = []

        target_files = self._target_files(diagnosis)
        for path in target_files:
            content = file_contents.get(path)
            if content is None:
                try:
                    content = await read_local_file(self.repo_root, path)
                except FileNotFoundError:
                    unfixable.append(f"{path}: 文件不存在，无法自动修复")
                    continue

            patch = self._fix_file(path, content, diagnosis)
            if patch:
                patches.append(patch)
            else:
                unfixable.append(f"{path}: 当前规则无法安全生成补丁")

        if patches:
            summary = f"生成 {len(patches)} 个修复补丁。"
            confidence = 0.85 if not unfixable else 0.7
        else:
            summary = "没有生成可安全应用的补丁。"
            confidence = 0.2
        return FixOutput(patches=patches, summary=summary, unfixable=unfixable, confidence=confidence)

    async def get_file_content(self, path: str, repo: str | None = None, ref: str | None = None) -> str:
        if repo and self.github:
            return await self.github.get_file_content(repo, path, ref=ref)
        return await read_local_file(self.repo_root, path)

    def search_codebase(self, query: str, top_k: int = 5) -> list[dict]:
        if not self.retriever:
            return []
        return [item.model_dump() for item in self.retriever.search_codebase(query, top_k=top_k)]

    def _target_files(self, diagnosis: ReviewOutput | CICDOutput) -> list[str]:
        if isinstance(diagnosis, ReviewOutput):
            return sorted({issue.file for issue in diagnosis.issues if issue.file})
        return sorted({path for path in diagnosis.affected_files if path})

    def _fix_file(self, path: str, content: str, diagnosis: ReviewOutput | CICDOutput) -> FilePatch | None:
        issue_refs: list[str] = []
        patched = content
        explanations: list[str] = []

        secret_fix = self._patch_hardcoded_secret(patched)
        if secret_fix:
            patched = secret_fix[0]
            issue_refs.append("hardcoded-secret")
            explanations.append(secret_fix[1])

        sql_fix = self._patch_sql_injection(patched)
        if sql_fix:
            patched = sql_fix[0]
            issue_refs.append("sql-injection")
            explanations.append(sql_fix[1])

        debug_fix = self._patch_debug_prints(patched)
        if debug_fix:
            patched = debug_fix[0]
            issue_refs.append("debug-print")
            explanations.append(debug_fix[1])

        rename_fix = self._patch_field_rename(patched, diagnosis)
        if rename_fix:
            patched = rename_fix[0]
            issue_refs.append("field-rename")
            explanations.append(rename_fix[1])

        if patched == content:
            return None
        return FilePatch(
            path=path,
            original=content,
            patched=patched,
            explanation="；".join(explanations),
            issue_ref=",".join(issue_refs) or "generic-fix",
        )

    def _patch_hardcoded_secret(self, content: str) -> tuple[str, str] | None:
        pattern = re.compile(
            r'^(?P<indent>\s*)(?P<name>[A-Z_]*(?:API_KEY|SECRET|TOKEN|PASSWORD)[A-Z_]*)\s*=\s*(?P<value>["\'][^"\']+["\'])\s*$',
            re.MULTILINE,
        )
        match = pattern.search(content)
        if not match:
            return None
        replacement = f'{match.group("indent")}{match.group("name")} = os.environ["{match.group("name")}"]'
        patched = content[: match.start()] + replacement + content[match.end() :]
        if "import os" not in patched:
            patched = self._inject_import(patched, "import os")
        return patched, "将硬编码密钥改为环境变量读取"

    def _patch_sql_injection(self, content: str) -> tuple[str, str] | None:
        pattern = re.compile(
            r'^(?P<indent>\s*)query\s*=\s*f["\'](?P<sql>SELECT .*? WHERE .*?= )\'\{(?P<var>[A-Za-z_][A-Za-z0-9_]*)\}\'["\']\s*$\n^(?P=indent)cursor\.execute\(query\)\s*$',
            re.MULTILINE,
        )
        match = pattern.search(content)
        if not match:
            return None
        sql = match.group("sql") + "%s"
        replacement = f'{match.group("indent")}cursor.execute("{sql}", ({match.group("var")},))'
        patched = content[: match.start()] + replacement + content[match.end() :]
        return patched, "把字符串拼接 SQL 改为参数化查询"

    def _patch_debug_prints(self, content: str) -> tuple[str, str] | None:
        pattern = re.compile(r'^\s*(print|console\.log)\(.*?\)\s*$\n?', re.MULTILINE)
        if not pattern.search(content):
            return None
        patched = pattern.sub("", content, count=1)
        return patched, "删除调试输出，避免把临时日志提交进仓库"

    def _patch_field_rename(self, content: str, diagnosis: ReviewOutput | CICDOutput) -> tuple[str, str] | None:
        if not isinstance(diagnosis, CICDOutput):
            return None
        combined = f"{diagnosis.root_cause}\n{diagnosis.fix_suggestion}"
        match = re.search(r'([A-Za-z_][\w]*)\["([A-Za-z_][\w]*)"\].*?([A-Za-z_][\w]*)\["([A-Za-z_][\w]*)"\]', combined)
        if not match:
            return None
        old_key = match.group(2)
        new_key = match.group(4)
        old = f'"{old_key}"'
        new = f'"{new_key}"'
        if old not in content:
            return None
        patched = content.replace(old, new, 1)
        return patched, f"把旧字段 `{old_key}` 替换为 `{new_key}`"

    def _inject_import(self, content: str, import_stmt: str) -> str:
        lines = content.splitlines()
        insert_at = 0
        if lines and lines[0].startswith("#!"):
            insert_at = 1
        while insert_at < len(lines) and (lines[insert_at].startswith("#") or not lines[insert_at].strip()):
            insert_at += 1
        lines.insert(insert_at, import_stmt)
        return "\n".join(lines) + ("\n" if content.endswith("\n") else "")
