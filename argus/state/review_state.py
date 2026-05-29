"""PR 审查状态存储。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from pydantic import BaseModel, Field

from argus.models import ReviewIssue


class PostedIssue(BaseModel):
    """已发出评论的记录。"""

    file: str
    line: int
    content_hash: str
    comment_id: int = 0


class ReviewState(BaseModel):
    """一个 PR 的审查状态。"""

    pr_key: str
    reviewed_commits: list[str] = Field(default_factory=list)
    posted_issues: list[PostedIssue] = Field(default_factory=list)


class ReviewStateStore:
    """用本地 JSON 文件存储审查状态。"""

    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def get_state(self, pr_key: str) -> ReviewState | None:
        """读取单个 PR 状态。"""

        state_map = self._load_all()
        payload = state_map.get(pr_key)
        return ReviewState(**payload) if payload else None

    def save_state(self, state: ReviewState) -> None:
        """保存单个 PR 状态。"""

        state_map = self._load_all()
        state_map[state.pr_key] = state.model_dump()
        self.path.write_text(json.dumps(state_map, ensure_ascii=False, indent=2), encoding="utf-8")

    def hash_issue(self, issue: ReviewIssue) -> str:
        """给评论内容做稳定哈希。"""

        payload = f"{issue.file}:{issue.line}:{issue.severity}:{issue.description}:{issue.suggestion}"
        return hashlib.sha1(payload.encode("utf-8")).hexdigest()

    def deduplicate(self, issues: list[ReviewIssue], posted: list[PostedIssue]) -> list[ReviewIssue]:
        """避免重复发同一个问题。"""

        posted_hashes = {item.content_hash for item in posted}
        return [issue for issue in issues if self.hash_issue(issue) not in posted_hashes]

    def to_posted_issues(self, issues: list[ReviewIssue]) -> list[PostedIssue]:
        """把新的 issue 转成持久化记录。"""

        return [
            PostedIssue(file=issue.file, line=issue.line, content_hash=self.hash_issue(issue))
            for issue in issues
        ]

    def _load_all(self) -> dict[str, dict]:
        if not self.path.exists():
            return {}
        return json.loads(self.path.read_text(encoding="utf-8"))
