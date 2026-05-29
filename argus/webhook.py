"""FastAPI Webhook 网关。"""

from __future__ import annotations

import hashlib
import hmac
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request

from argus.config import load_settings
from argus.models import GitHubEvent
from argus.orchestrator import Orchestrator, build_default_orchestrator


def create_app(orchestrator: Orchestrator | None = None, repo_root: Path | None = None) -> FastAPI:
    """创建 FastAPI 应用。"""

    app = FastAPI(title="Argus", version="0.1.0")
    settings = load_settings()
    orchestrator_instance = orchestrator or build_default_orchestrator(repo_root=repo_root)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/webhook")
    async def webhook(request: Request) -> dict:
        body = await request.body()
        _verify_signature(body, request.headers.get("X-Hub-Signature-256", ""), settings.github_webhook_secret)
        event_name = request.headers.get("X-GitHub-Event", "")
        payload = await request.json()
        event = build_event(event_name, payload)
        result = await orchestrator_instance.handle(event)
        return result.model_dump()

    return app


def _verify_signature(body: bytes, signature: str, secret: str) -> None:
    if not secret:
        return
    expected = "sha256=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(signature, expected):
        raise HTTPException(status_code=401, detail="Invalid signature")


def build_event(event_name: str, payload: dict) -> GitHubEvent:
    """把 GitHub payload 转成统一事件。"""

    repo = payload["repository"]["full_name"]
    installation_id = payload.get("installation", {}).get("id", 0)
    if event_name == "pull_request":
        pr = payload["pull_request"]
        return GitHubEvent(
            type="pull_request",
            action=payload.get("action", "opened"),
            repo=repo,
            pr_number=pr["number"],
            diff_url=pr.get("diff_url"),
            installation_id=installation_id,
            head_sha=pr.get("head", {}).get("sha", ""),
            pr_title=pr.get("title", ""),
            pr_body=pr.get("body", ""),
            changed_files=[],
        )

    if event_name == "check_run":
        check_run = payload["check_run"]
        pr_refs = check_run.get("pull_requests", [])
        pr_number = pr_refs[0]["number"] if pr_refs else None
        return GitHubEvent(
            type="check_run",
            action=payload.get("action", "completed"),
            repo=repo,
            pr_number=pr_number,
            build_log_url=check_run.get("html_url"),
            conclusion=check_run.get("conclusion"),
            installation_id=installation_id,
            run_id=check_run.get("check_suite", {}).get("id") or check_run.get("id"),
            head_sha=check_run.get("head_sha", ""),
        )

    raise HTTPException(status_code=400, detail=f"Unsupported event: {event_name}")
