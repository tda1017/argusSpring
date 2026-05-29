"""补丁预览与应用工具。"""

from __future__ import annotations

import difflib
from pathlib import Path

from argus.models import FilePatch


class PatchApplyResult:
    """补丁应用结果。"""

    def __init__(self, applied: list[str] | None = None, failed: list[dict[str, str]] | None = None):
        self.applied = applied or []
        self.failed = failed or []


def render_patch_diff(patch: FilePatch) -> str:
    """把单个补丁渲染成 unified diff。"""

    original_lines = patch.original.splitlines(keepends=True)
    patched_lines = patch.patched.splitlines(keepends=True)
    diff = difflib.unified_diff(
        original_lines,
        patched_lines,
        fromfile=f"a/{patch.path}",
        tofile=f"b/{patch.path}",
    )
    return "".join(diff).strip()


def apply_file_patches(repo_root: Path, patches: list[FilePatch], dry_run: bool = True) -> PatchApplyResult:
    """把补丁应用到工作区。"""

    result = PatchApplyResult()
    for patch in patches:
        target = repo_root / patch.path
        if not target.exists():
            result.failed.append({"path": patch.path, "reason": "file not found"})
            continue

        current = target.read_text(encoding="utf-8")
        if current == patch.original:
            next_content = patch.patched
        elif patch.original and patch.original in current:
            next_content = current.replace(patch.original, patch.patched, 1)
        else:
            result.failed.append({"path": patch.path, "reason": "original snippet not found"})
            continue

        if not dry_run:
            target.write_text(next_content, encoding="utf-8")
        result.applied.append(patch.path)
    return result
