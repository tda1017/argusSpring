"""Review 输出的噪音控制过滤器。"""

from __future__ import annotations

import ast
import re
from collections import defaultdict

from argus.models import ReviewIssue
from argus.project_rules import extract_markdown_section


class ReviewPostFilter:
    """控制真正发出去的评论数量和质量。"""

    def __init__(
        self,
        max_comments: int = 8,
        max_comments_per_file: int = 3,
        severity_threshold: str = "warning",
        ignore_categories: list[str] | None = None,
    ):
        self.max_comments = max_comments
        self.max_comments_per_file = max_comments_per_file
        self.severity_threshold = severity_threshold
        self.ignore_categories = set(ignore_categories or [])

    @classmethod
    def from_rules(cls, rules: str | None) -> "ReviewPostFilter":
        """从 argus.md 读取噪音控制配置。"""

        if not rules:
            return cls()
        section = extract_markdown_section(rules, "审查噪音控制")
        if not section:
            return cls()

        config: dict[str, object] = {}
        for raw_line in section.splitlines():
            match = re.match(r"^-\s*([\w-]+)\s*:\s*(.+?)(?:\s+#.*)?$", raw_line.strip())
            if not match:
                continue
            key = match.group(1).strip().lower().replace("-", "_")
            value = match.group(2).strip()
            if key in {"max_comments", "max_comments_per_file"}:
                config[key] = int(value)
            elif key == "ignore_categories":
                try:
                    config[key] = [str(item) for item in ast.literal_eval(value)]
                except (ValueError, SyntaxError):
                    config[key] = [part.strip() for part in value.split(",") if part.strip()]
            else:
                config[key] = value
        return cls(**config)

    def filter(self, issues: list[ReviewIssue]) -> list[ReviewIssue]:
        """按严重性和数量限制裁剪评论。"""

        filtered = [issue for issue in issues if self._meets_threshold(issue.severity)]
        filtered = [issue for issue in filtered if issue.category not in self.ignore_categories]

        by_file: dict[str, list[ReviewIssue]] = defaultdict(list)
        for issue in filtered:
            by_file[issue.file].append(issue)

        result: list[ReviewIssue] = []
        for file_name in sorted(by_file):
            file_issues = sorted(by_file[file_name], key=self._severity_rank)
            result.extend(file_issues[: self.max_comments_per_file])

        result.sort(key=self._severity_rank)
        return result[: self.max_comments]

    def _meets_threshold(self, severity: str) -> bool:
        return self._severity_rank_value(severity) <= self._severity_rank_value(self.severity_threshold)

    def _severity_rank(self, issue: ReviewIssue) -> tuple[int, str, int]:
        return (self._severity_rank_value(issue.severity), issue.file, issue.line)

    def _severity_rank_value(self, severity: str) -> int:
        rank = {"critical": 0, "warning": 1, "suggestion": 2}
        return rank.get(severity, 99)
