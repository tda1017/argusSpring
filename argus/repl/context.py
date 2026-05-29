"""REPL 的上下文检测与会话状态。"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from argus.models import CIStatus, CICDOutput, FailedJob, FixOutput, GitContext, PRInfo, ReviewOutput


@dataclass
class Session:
    """REPL 会话状态。"""

    messages: list[dict] = field(default_factory=list)
    repo_root: str | None = None
    git_branch: str | None = None
    repo: str | None = None
    pr_number: int | None = None
    pr_title: str | None = None
    cached_diff: str | None = None
    cached_review: ReviewOutput | None = None
    cached_diagnosis: CICDOutput | None = None
    cached_fix: FixOutput | None = None
    last_diagnosis: ReviewOutput | CICDOutput | None = None
    cached_ci_log: str | None = None
    ci_status: str = "none"
    has_argus_rules: bool = False

    def add_message(self, role: str, content: str) -> None:
        self.messages.append({"role": role, "content": content})

    def clear(self) -> None:
        self.messages.clear()
        self.cached_diff = None
        self.cached_review = None
        self.cached_diagnosis = None
        self.cached_fix = None
        self.last_diagnosis = None
        self.cached_ci_log = None


def detect_context(repo_root: Path | None = None) -> Session:
    """启动时自动检测 git/PR/CI 上下文。"""

    root = repo_root or Path.cwd()
    session = Session()

    git_context = collect_git_context(root)
    session.repo_root = git_context.repo_root
    session.git_branch = git_context.branch

    if session.repo_root:
        session.repo = infer_repo_slug(Path(session.repo_root))
        session.has_argus_rules = any((Path(session.repo_root) / name).exists() for name in ["argus.md", ".argus/config.md", "ARGUS.md"])

    pr_info = collect_pr_info(root, repo=session.repo)
    if pr_info.pr_number:
        session.pr_number = pr_info.pr_number
        session.pr_title = pr_info.title
        session.cached_diff = pr_info.diff

    ci_status = collect_ci_status(root, repo=session.repo, pr_number=session.pr_number)
    session.ci_status = ci_status.status
    session.cached_ci_log = ci_status.log_text
    return session


def collect_git_context(repo_root: Path | None = None, staged: bool = True) -> GitContext:
    """收集本地 git 上下文。"""

    cwd = _resolve_repo_root(repo_root)
    if cwd is None:
        return GitContext()

    branch = _run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd)
    diff_cmd = ["git", "diff", "--staged"] if staged else ["git", "diff"]
    staged_diff = _run(diff_cmd, cwd)
    recent = _run(["git", "log", "--oneline", "-5"], cwd)
    status = _run(["git", "status", "--porcelain"], cwd)
    return GitContext(
        repo_root=str(cwd),
        branch=branch or None,
        staged_diff=staged_diff or None,
        recent_commits=recent.splitlines() if recent else [],
        has_uncommitted=bool(status.strip()),
    )


def collect_pr_info(repo_root: Path | None = None, repo: str | None = None, pr_number: int | None = None) -> PRInfo:
    """尽量从 gh CLI 获取当前 PR 信息。"""

    cwd = _resolve_repo_root(repo_root)
    if cwd is None:
        return PRInfo()

    if shutil.which("gh"):
        try:
            args = ["gh", "pr", "view", "--json", "number,title,body,state"]
            if pr_number:
                args.insert(3, str(pr_number))
            payload = json.loads(_run(args, cwd))
            diff_args = ["gh", "pr", "diff"]
            number = payload.get("number")
            if number:
                diff_args.append(str(number))
            diff = _run(diff_args, cwd)
            checks_status = collect_ci_status(cwd, repo=repo, pr_number=number).status
            return PRInfo(
                pr_number=number,
                title=payload.get("title", ""),
                body=payload.get("body", ""),
                diff=diff or None,
                state=payload.get("state", "unknown").lower(),
                checks_status=checks_status,
            )
        except Exception:
            pass

    diff = _run(["git", "diff", "--staged"], cwd)
    if not diff:
        diff = _run(["git", "diff"], cwd)
    return PRInfo(pr_number=pr_number, diff=diff or None)


def collect_ci_status(repo_root: Path | None = None, repo: str | None = None, pr_number: int | None = None) -> CIStatus:
    """尽量获取当前分支最近一次 CI 状态。"""

    cwd = _resolve_repo_root(repo_root)
    if cwd is None or not shutil.which("gh"):
        return CIStatus(status="none")

    branch = _run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd)
    if not branch:
        return CIStatus(status="none")

    try:
        payload = json.loads(_run(["gh", "run", "list", "--branch", branch, "--limit", "1", "--json", "databaseId,status,conclusion,url,workflowName"], cwd))
        if not payload:
            return CIStatus(status="none")
        run = payload[0]
        status = run.get("status", "")
        conclusion = run.get("conclusion")
        if status != "completed":
            return CIStatus(status="pending", run_url=run.get("url"))
        if conclusion == "success":
            return CIStatus(status="success", run_url=run.get("url"))

        log_text = _run(["gh", "run", "view", str(run["databaseId"]), "--log-failed"], cwd)
        excerpt = "\n".join(log_text.splitlines()[:20]) if log_text else ""
        failed_jobs = [FailedJob(name=run.get("workflowName", "failed-job"), log_excerpt=excerpt)] if excerpt else []
        return CIStatus(status="failure", failed_jobs=failed_jobs, run_url=run.get("url"), log_text=log_text or None)
    except Exception:
        return CIStatus(status="none")


def get_review_diff(session: Session, staged: bool = False) -> str | None:
    """为 /review 获取最合适的 diff。"""

    if session.cached_diff and not staged:
        return session.cached_diff
    if session.repo_root is None:
        return None

    cwd = Path(session.repo_root)
    if not staged and shutil.which("gh") and session.pr_number:
        try:
            diff = _run(["gh", "pr", "diff", str(session.pr_number)], cwd)
            if diff:
                return diff
        except Exception:
            pass

    if staged:
        diff = _run(["git", "diff", "--staged"], cwd)
        return diff or None

    diff = _run(["git", "diff"], cwd)
    if diff:
        return diff
    diff = _run(["git", "diff", "--staged"], cwd)
    return diff or None


def infer_repo_slug(repo_root: Path) -> str | None:
    """从 origin URL 推断 owner/repo。"""

    remote = _run(["git", "remote", "get-url", "origin"], repo_root)
    if not remote:
        return None
    match = re.search(r"[:/]([^/:]+)/([^/]+?)(?:\.git)?$", remote.strip())
    if not match:
        return None
    return f"{match.group(1)}/{match.group(2)}"


def _resolve_repo_root(repo_root: Path | None) -> Path | None:
    cwd = repo_root or Path.cwd()
    try:
        resolved = _run(["git", "rev-parse", "--show-toplevel"], cwd)
    except Exception:
        return None
    return Path(resolved) if resolved else None


def _run(command: list[str], cwd: Path) -> str:
    completed = subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=True)
    return completed.stdout.strip()
