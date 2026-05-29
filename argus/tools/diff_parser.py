"""统一 diff 解析与分块。"""

from __future__ import annotations

import re

from argus.models import DiffChunk, DiffFile, DiffHunk, DiffLine


HUNK_RE = re.compile(r"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@")


def parse_unified_diff(diff: str) -> list[DiffFile]:
    """解析 unified diff，尽量保留新文件侧的行号。"""

    files: list[DiffFile] = []
    current_file: DiffFile | None = None
    current_hunk: DiffHunk | None = None
    old_line = 0
    new_line = 0

    for raw_line in diff.splitlines():
        if raw_line.startswith("diff --git "):
            if current_hunk and current_file:
                current_file.hunks.append(current_hunk)
                current_hunk = None
            if current_file:
                files.append(current_file)

            parts = raw_line.split()
            old_path = parts[2][2:] if len(parts) > 2 else None
            new_path = parts[3][2:] if len(parts) > 3 else None
            current_file = DiffFile(path=new_path or old_path or "unknown", old_path=old_path, new_path=new_path)
            continue

        if current_file is None:
            continue

        if raw_line.startswith("new file mode"):
            current_file.is_new = True
            continue
        if raw_line.startswith("deleted file mode"):
            current_file.is_deleted = True
            continue
        if raw_line.startswith("--- "):
            path = raw_line[4:].strip()
            current_file.old_path = None if path == "/dev/null" else path.removeprefix("a/")
            continue
        if raw_line.startswith("+++ "):
            path = raw_line[4:].strip()
            current_file.new_path = None if path == "/dev/null" else path.removeprefix("b/")
            current_file.path = current_file.new_path or current_file.old_path or current_file.path
            continue

        hunk_match = HUNK_RE.match(raw_line)
        if hunk_match:
            if current_hunk:
                current_file.hunks.append(current_hunk)
            old_line = int(hunk_match.group(1))
            new_line = int(hunk_match.group(3))
            current_hunk = DiffHunk(header=raw_line)
            continue

        if current_hunk is None:
            continue

        current_hunk.raw_lines.append(raw_line)
        if raw_line.startswith("+") and not raw_line.startswith("+++"):
            current_hunk.added_lines.append(DiffLine(line_number=new_line, content=raw_line[1:]))
            new_line += 1
            continue
        if raw_line.startswith("-") and not raw_line.startswith("---"):
            current_hunk.removed_lines.append(DiffLine(line_number=old_line, content=raw_line[1:]))
            old_line += 1
            continue

        content = raw_line[1:] if raw_line.startswith(" ") else raw_line
        current_hunk.context_lines.append(DiffLine(line_number=new_line, content=content))
        old_line += 1
        new_line += 1

    if current_hunk and current_file:
        current_file.hunks.append(current_hunk)
    if current_file:
        files.append(current_file)
    return files


def chunk_diff(diff: str, max_lines_per_chunk: int = 300) -> list[DiffChunk]:
    """按文件或 hunk 给大 diff 分块。"""

    chunks: list[DiffChunk] = []
    for diff_file in parse_unified_diff(diff):
        if diff_file.line_count <= max_lines_per_chunk:
            start_line = None
            for hunk in diff_file.hunks:
                if hunk.added_lines:
                    start_line = hunk.added_lines[0].line_number
                    break
            chunks.append(DiffChunk(file=diff_file.path, content=diff_file.text, start_line=start_line))
            continue

        for hunk in diff_file.hunks:
            start_line = hunk.added_lines[0].line_number if hunk.added_lines else None
            chunks.append(DiffChunk(file=diff_file.path, content=hunk.text, start_line=start_line))
    return chunks


def changed_files(diff: str) -> list[str]:
    """提取 diff 中涉及的文件列表。"""

    return [item.path for item in parse_unified_diff(diff)]
