"""GitHub API 封装。"""

from __future__ import annotations

import asyncio
import base64
import io
import time
import zipfile
from pathlib import Path
from typing import Any

import httpx
import jwt

from argus.config import Settings, load_settings
from argus.tools.log_parser import preprocess_log


class GitHubClient:
    """支持 GitHub App 和 Token 的轻量客户端。"""

    def __init__(self, settings: Settings | None = None, client: httpx.AsyncClient | None = None):
        self.settings = settings or load_settings()
        self.client = client or httpx.AsyncClient(base_url="https://api.github.com", timeout=30.0)

    async def close(self) -> None:
        """关闭底层 HTTP 连接。"""

        await self.client.aclose()

    async def get_pr_diff(self, repo: str, pr_number: int, installation_id: int = 0) -> str:
        """获取 PR 的 unified diff。"""

        response = await self._request(
            "GET",
            f"/repos/{repo}/pulls/{pr_number}",
            installation_id=installation_id,
            headers={"Accept": "application/vnd.github.v3.diff"},
        )
        return response.text

    async def get_file_content(self, repo: str, path: str, ref: str | None = None, installation_id: int = 0) -> str:
        """读取仓库中文件内容。"""

        params = {"ref": ref} if ref else None
        response = await self._request(
            "GET",
            f"/repos/{repo}/contents/{path}",
            installation_id=installation_id,
            params=params,
        )
        payload = response.json()
        content = payload.get("content", "")
        encoding = payload.get("encoding")
        if encoding == "base64":
            return base64.b64decode(content).decode("utf-8", errors="ignore")
        return content

    async def get_build_log(
        self,
        repo: str,
        run_id: int,
        installation_id: int = 0,
        job_name: str | None = None,
    ) -> str:
        """下载 GitHub Actions 日志并做预处理。"""

        response = await self._request(
            "GET",
            f"/repos/{repo}/actions/runs/{run_id}/logs",
            installation_id=installation_id,
            follow_redirects=True,
        )
        archive = zipfile.ZipFile(io.BytesIO(response.content))
        chunks: list[str] = []
        for file_name in sorted(archive.namelist()):
            if job_name and job_name not in file_name:
                continue
            chunks.append(archive.read(file_name).decode("utf-8", errors="ignore"))
        return preprocess_log("\n".join(chunks))

    async def create_pr_comment(self, repo: str, pr_number: int, body: str, installation_id: int = 0) -> str:
        """在 PR 上发总评评论。"""

        response = await self._request(
            "POST",
            f"/repos/{repo}/issues/{pr_number}/comments",
            installation_id=installation_id,
            json={"body": body},
        )
        return response.json().get("html_url", "")

    async def create_review_comment(
        self,
        repo: str,
        pr_number: int,
        path: str,
        line: int,
        body: str,
        installation_id: int = 0,
        commit_id: str | None = None,
    ) -> str:
        """在变更行上发 inline comment。"""

        payload: dict[str, Any] = {"body": body, "path": path, "line": line, "side": "RIGHT"}
        if commit_id:
            payload["commit_id"] = commit_id

        response = await self._request(
            "POST",
            f"/repos/{repo}/pulls/{pr_number}/comments",
            installation_id=installation_id,
            json=payload,
        )
        return response.json().get("html_url", "")

    async def get_pr_commits(self, repo: str, pr_number: int, installation_id: int = 0) -> list[str]:
        """返回 PR 的 commit SHA 列表。"""

        response = await self._request(
            "GET",
            f"/repos/{repo}/pulls/{pr_number}/commits",
            installation_id=installation_id,
        )
        return [item["sha"] for item in response.json()]

    async def get_pr_commits_since(self, repo: str, pr_number: int, since: str, installation_id: int = 0) -> list[str]:
        """返回某次审查之后新增的 commits。"""

        commits = await self.get_pr_commits(repo, pr_number, installation_id=installation_id)
        if since not in commits:
            return commits
        index = commits.index(since)
        return commits[index + 1 :]

    async def get_compare_diff(self, repo: str, base: str, head: str, installation_id: int = 0) -> str:
        """获取两个提交之间的 diff。"""

        response = await self._request(
            "GET",
            f"/repos/{repo}/compare/{base}...{head}",
            installation_id=installation_id,
            headers={"Accept": "application/vnd.github.v3.diff"},
        )
        return response.text

    async def get_comment_reactions(self, repo: str, comment_id: int, installation_id: int = 0) -> dict[str, int]:
        """获取评论 reaction 分布。"""

        response = await self._request(
            "GET",
            f"/repos/{repo}/issues/comments/{comment_id}/reactions",
            installation_id=installation_id,
            headers={"Accept": "application/vnd.github+json"},
        )
        counts: dict[str, int] = {}
        for item in response.json():
            key = item.get("content", "")
            counts[key] = counts.get(key, 0) + 1
        return counts

    async def _request(self, method: str, url: str, installation_id: int = 0, **kwargs: Any) -> httpx.Response:
        """统一带认证和重试的 HTTP 请求。"""

        headers = dict(kwargs.pop("headers", {}))
        headers.setdefault("Accept", "application/vnd.github+json")
        headers.setdefault("X-GitHub-Api-Version", "2022-11-28")
        auth_headers = await self._auth_headers(installation_id)
        headers.update(auth_headers)

        for attempt in range(3):
            response = await self.client.request(method, url, headers=headers, **kwargs)
            if response.status_code not in {429, 500, 502, 503, 504}:
                response.raise_for_status()
                return response
            await asyncio.sleep(2**attempt)
        response.raise_for_status()
        return response

    async def _auth_headers(self, installation_id: int) -> dict[str, str]:
        if self.settings.github_token:
            return {"Authorization": f"Bearer {self.settings.github_token}"}
        if not (self.settings.github_app_id and self.settings.github_private_key and installation_id):
            return {}
        token = await self.get_installation_token(installation_id)
        return {"Authorization": f"Bearer {token}"}

    async def get_installation_token(self, installation_id: int) -> str:
        """生成 installation access token。"""

        now = int(time.time())
        encoded = jwt.encode(
            {"iat": now - 60, "exp": now + 600, "iss": self.settings.github_app_id},
            self.settings.github_private_key,
            algorithm="RS256",
        )
        response = await self.client.post(
            f"https://api.github.com/app/installations/{installation_id}/access_tokens",
            headers={
                "Authorization": f"Bearer {encoded}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        response.raise_for_status()
        return response.json()["token"]


async def read_local_file(repo_root: Path, path: str) -> str:
    """本地模式下读取文件。"""

    return (repo_root / path).read_text(encoding="utf-8", errors="ignore")
