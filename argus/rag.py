"""本地索引与检索实现。"""

from __future__ import annotations

import json
import math
import re
from pathlib import Path

from pydantic import BaseModel, Field

from argus.models import SearchResult
from argus.project_rules import extract_markdown_section, find_project_rules


INDEXABLE_SUFFIXES = {
    ".py",
    ".js",
    ".ts",
    ".tsx",
    ".jsx",
    ".go",
    ".rs",
    ".java",
    ".md",
    ".toml",
    ".yaml",
    ".yml",
    ".json",
}
IGNORED_DIRS = {".git", ".venv", "node_modules", "dist", "build", ".mypy_cache", ".pytest_cache", ".argus"}


class IndexChunk(BaseModel):
    """索引里的最小文档块。"""

    doc_id: str
    path: str
    content: str
    start_line: int
    chunk_type: str = "code"
    metadata: dict[str, str] = Field(default_factory=dict)


class LocalRetriever:
    """基于 JSON 的轻量检索器。"""

    def __init__(self, repo_root: Path, index_dir: Path | None = None):
        self.repo_root = repo_root
        self.index_dir = index_dir or (repo_root / ".argus/index")
        self.index_file = self.index_dir / "index.json"

    def index_repository(self, path: Path | None = None) -> int:
        """扫描仓库并建立本地索引。"""

        target = (path or self.repo_root).resolve()
        chunks: list[IndexChunk] = []
        for file_path in sorted(target.rglob("*")):
            if not file_path.is_file():
                continue
            if any(part in IGNORED_DIRS for part in file_path.parts):
                continue
            if file_path.suffix.lower() not in INDEXABLE_SUFFIXES:
                continue
            relative_path = file_path.relative_to(target).as_posix()
            content = file_path.read_text(encoding="utf-8", errors="ignore")
            chunks.extend(self._chunk_file(relative_path, content))

        rules = find_project_rules(target)
        if rules:
            chunks.append(
                IndexChunk(
                    doc_id="rules::argus.md",
                    path="argus.md",
                    content=rules,
                    start_line=1,
                    chunk_type="rule",
                )
            )

        payload = [chunk.model_dump() for chunk in chunks]
        self.index_dir.mkdir(parents=True, exist_ok=True)
        self.index_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return len(chunks)


    def search_codebase(self, query: str, top_k: int = 5) -> list[SearchResult]:
        """搜索索引中的代码片段。"""

        results = self._search(query=query, top_k=top_k, allowed_types={"code", "doc", "rule"})
        return results

    def search_solutions(self, error_message: str, top_k: int = 3) -> list[dict[str, str]]:
        """搜索和错误文本最相关的文档块。"""

        results = self._search(query=error_message, top_k=top_k, allowed_types={"doc", "rule", "code"})
        payload = []
        for result in results:
            payload.append(
                {
                    "error": error_message,
                    "solution": result.content.splitlines()[0] if result.content else "No solution found",
                    "repo": str(self.repo_root),
                    "date": "local-index",
                }
            )
        return payload

    def get_project_rules(self, topic: str) -> str:
        """返回规则全文或特定小节。"""

        rules = find_project_rules(self.repo_root)
        if not rules:
            return ""
        section_map = {
            "general": "代码规范",
            "naming": "代码规范",
            "error-handling": "代码规范",
            "testing": "审查重点",
        }
        section_name = section_map.get(topic.strip().lower())
        if not section_name:
            return rules
        return extract_markdown_section(rules, section_name) or rules

    def _load_chunks(self) -> list[IndexChunk]:
        if not self.index_file.exists():
            return []
        payload = json.loads(self.index_file.read_text(encoding="utf-8"))
        return [IndexChunk(**item) for item in payload]

    def _chunk_file(self, relative_path: str, content: str) -> list[IndexChunk]:
        lines = content.splitlines()
        chunks: list[IndexChunk] = []
        buffer: list[str] = []
        start_line = 1
        chunk_index = 0
        for line_number, line in enumerate(lines, start=1):
            if not buffer:
                start_line = line_number
            buffer.append(line)
            if len(buffer) >= 50 or (line.strip() == "" and len(buffer) >= 12):
                chunk_index += 1
                chunks.append(
                    IndexChunk(
                        doc_id=f"{relative_path}::{chunk_index}",
                        path=relative_path,
                        content="\n".join(buffer).strip(),
                        start_line=start_line,
                        chunk_type="doc" if relative_path.endswith(".md") else "code",
                    )
                )
                buffer = []

        if buffer:
            chunk_index += 1
            chunks.append(
                IndexChunk(
                    doc_id=f"{relative_path}::{chunk_index}",
                    path=relative_path,
                    content="\n".join(buffer).strip(),
                    start_line=start_line,
                    chunk_type="doc" if relative_path.endswith(".md") else "code",
                )
            )
        return [chunk for chunk in chunks if chunk.content]

    def _search(self, query: str, top_k: int, allowed_types: set[str]) -> list[SearchResult]:
        chunks = self._load_chunks()
        if not chunks:
            return []

        query_tokens = _tokenize(query)
        scored: list[tuple[float, IndexChunk]] = []
        for chunk in chunks:
            if chunk.chunk_type not in allowed_types:
                continue
            score = _score(query_tokens, chunk.content)
            if score <= 0:
                continue
            scored.append((score, chunk))

        scored.sort(key=lambda item: item[0], reverse=True)
        return [
            SearchResult(content=chunk.content, file=chunk.path, start_line=chunk.start_line, score=score)
            for score, chunk in scored[:top_k]
        ]


def _tokenize(text: str) -> list[str]:
    return [token for token in re.findall(r"[A-Za-z_][A-Za-z0-9_./:-]*", text.lower()) if len(token) > 1]


def _score(query_tokens: list[str], content: str) -> float:
    content_tokens = _tokenize(content)
    if not query_tokens or not content_tokens:
        return 0.0
    content_set = set(content_tokens)
    overlap = sum(1 for token in query_tokens if token in content_set)
    if overlap == 0:
        return 0.0
    density = overlap / math.sqrt(len(content_set))
    exact_bonus = 0.5 if " ".join(query_tokens) in content.lower() else 0.0
    return round(density + exact_bonus, 6)
