"""规则路由的 Orchestrator。"""

from __future__ import annotations

import asyncio
import time
from pathlib import Path

from argus.agents.cicd import CICDAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.config import load_settings
from argus.github import GitHubClient
from argus.models import AgentFailure, CICDInput, CICDOutput, FixInput, FixOutput, GitHubEvent, OrchestratorResult, ReviewInput, ReviewOutput
from argus.patching import render_patch_diff
from argus.project_rules import find_project_rules
from argus.rag import LocalRetriever
from argus.state.review_state import ReviewState, ReviewStateStore
from argus.tools.diff_parser import changed_files


class Orchestrator:
    """纯规则调度，不把路由丢给 LLM。"""

    def __init__(
        self,
        review_agent: ReviewAgent,
        cicd_agent: CICDAgent,
        github: GitHubClient,
        state_store: ReviewStateStore | None = None,
        fix_agent: FixAgent | None = None,
        auto_fix: bool = False,
        fix_confidence_threshold: float = 0.8,
    ):
        self.review_agent = review_agent
        self.cicd_agent = cicd_agent
        self.fix_agent = fix_agent
        self.github = github
        self.state_store = state_store or ReviewStateStore(Path(".argus/state/review-state.json"))
        self.auto_fix = auto_fix
        self.fix_confidence_threshold = fix_confidence_threshold

    async def handle(self, event: GitHubEvent) -> OrchestratorResult:
        """根据事件类型调度对应 Agent。"""

        started = time.monotonic()
        tasks: list[tuple[str, asyncio.Future]] = []
        traces: dict[str, str] = {}

        if event.type == "pull_request" and event.action in {"opened", "synchronize"} and event.pr_number:
            review_input = await self._build_review_input(event)
            tasks.append(("review", asyncio.create_task(self.review_agent.run(review_input))))

        if event.type == "check_run" and event.conclusion == "failure" and event.run_id:
            cicd_input = await self._build_cicd_input(event)
            tasks.append(("cicd", asyncio.create_task(self.cicd_agent.run(cicd_input))))

        results: list[ReviewOutput | CICDOutput | FixOutput | AgentFailure] = []
        invoked: list[str] = []
        if tasks:
            names, coroutines = zip(*tasks)
            outputs = await asyncio.gather(*coroutines, return_exceptions=True)
            for name, output in zip(names, outputs):
                invoked.append(name)
                if isinstance(output, Exception):
                    results.append(AgentFailure(agent=name, error=str(output)))
                    continue
                results.append(output)
                agent = self.review_agent if name == "review" else self.cicd_agent
                if agent.last_trace:
                    traces[name] = agent.last_trace.to_markdown(agent.model_name)

        if event.type == "pull_request" and event.pr_number and event.head_sha:
            self._apply_review_state(event, results)

        fix_output = await self._maybe_generate_fix(event, results)
        if fix_output is not None:
            results.append(fix_output)
            invoked.append("fix")
            if self.fix_agent and self.fix_agent.last_trace:
                traces["fix"] = self.fix_agent.last_trace.to_markdown(self.fix_agent.model_name)

        if not results:
            results = [AgentFailure(agent="orchestrator", error="No matching agent for this event")]

        comment = self.format_comment(results, traces)
        if event.pr_number:
            await self.github.create_pr_comment(event.repo, event.pr_number, comment, installation_id=event.installation_id)

        elapsed = int((time.monotonic() - started) * 1000)
        return OrchestratorResult(
            event_type=event.type,
            agents_invoked=invoked,
            results=results,
            formatted_comment=comment,
            execution_time_ms=elapsed,
        )

    def format_comment(self, results: list, traces: dict[str, str] | None = None) -> str:
        """把多个 Agent 结果合并成一条 GitHub 评论。"""

        sections: list[str] = []
        for item in results:
            if getattr(item, "root_cause", None) is not None:
                sections.append("## 🔧 CI/CD Analysis\n\n" + item.to_markdown())
            elif getattr(item, "issues", None) is not None:
                sections.append("## 🔍 Code Review\n\n" + item.to_markdown())
            elif getattr(item, "patches", None) is not None:
                body = item.to_markdown()
                for patch in item.patches[:3]:
                    body += f"\n\n```diff\n{render_patch_diff(patch)}\n```"
                sections.append("## 🩹 Fix Proposal\n\n" + body)
            else:
                sections.append("## ⚠️ Agent Failure\n\n" + item.to_markdown())

        if traces:
            sections.extend(traces.values())
        sections.append("> _Powered by Argus_ · 👍 Helpful · 👎 Not helpful")
        return "\n\n---\n\n".join(sections)

    async def _build_review_input(self, event: GitHubEvent) -> ReviewInput:
        pr_key = f"{event.repo}#{event.pr_number}"
        state = self.state_store.get_state(pr_key)
        if event.action == "synchronize" and state and state.reviewed_commits:
            diff = await self.github.get_compare_diff(
                event.repo,
                state.reviewed_commits[-1],
                event.head_sha,
                installation_id=event.installation_id,
            )
        else:
            diff = await self.github.get_pr_diff(event.repo, event.pr_number, installation_id=event.installation_id)

        return ReviewInput(
            repo=event.repo,
            pr_number=event.pr_number,
            diff=diff,
            changed_files=event.changed_files or changed_files(diff),
            pr_title=event.pr_title,
            pr_body=event.pr_body,
            ref=event.head_sha,
        )

    async def _build_cicd_input(self, event: GitHubEvent) -> CICDInput:
        log_text = await self.github.get_build_log(event.repo, event.run_id, installation_id=event.installation_id)
        return CICDInput(
            repo=event.repo,
            run_id=event.run_id,
            run_url=event.build_log_url or "",
            conclusion=event.conclusion or "failure",
            head_sha=event.head_sha,
            log_text=log_text,
        )

    async def _maybe_generate_fix(self, event: GitHubEvent, results: list) -> FixOutput | None:
        if not (self.auto_fix and self.fix_agent and event.pr_number):
            return None

        review_output = next((item for item in results if isinstance(item, ReviewOutput)), None)
        cicd_output = next((item for item in results if isinstance(item, CICDOutput)), None)
        diagnosis: ReviewOutput | CICDOutput | None = None

        if review_output and any(issue.severity == "critical" for issue in review_output.issues):
            diagnosis = ReviewOutput(
                summary=review_output.summary,
                approval=review_output.approval,
                issues=[issue for issue in review_output.issues if issue.severity == "critical"],
            )
        elif cicd_output and cicd_output.affected_files:
            diagnosis = cicd_output

        if diagnosis is None:
            return None

        file_paths = self._diagnosis_files(diagnosis)
        file_contents: dict[str, str] = {}
        for path in file_paths:
            try:
                file_contents[path] = await self.github.get_file_content(
                    event.repo,
                    path,
                    ref=event.head_sha or None,
                    installation_id=event.installation_id,
                )
            except Exception:
                continue

        fix_output = await self.fix_agent.run(
            FixInput(
                diagnosis=diagnosis,
                file_contents=file_contents,
                repo_root=str(self.fix_agent.repo_root),
                rules=find_project_rules(self.fix_agent.repo_root),
            )
        )
        if not fix_output.patches or fix_output.confidence < self.fix_confidence_threshold:
            return None
        fix_output.summary = f"自动生成修复建议（confidence={fix_output.confidence:.2f}）。{fix_output.summary}"
        return fix_output

    def _diagnosis_files(self, diagnosis: ReviewOutput | CICDOutput) -> list[str]:
        if isinstance(diagnosis, ReviewOutput):
            return sorted({issue.file for issue in diagnosis.issues if issue.file})
        return sorted({path for path in diagnosis.affected_files if path})

    def _apply_review_state(self, event: GitHubEvent, results: list) -> None:
        review_output = next((item for item in results if getattr(item, "issues", None) is not None), None)
        if review_output is None:
            return
        pr_key = f"{event.repo}#{event.pr_number}"
        existing = self.state_store.get_state(pr_key)
        reviewed_commits = list(existing.reviewed_commits) if existing else []
        if event.head_sha and event.head_sha not in reviewed_commits:
            reviewed_commits.append(event.head_sha)
        posted_issues = list(existing.posted_issues) if existing else []
        deduped = self.state_store.deduplicate(review_output.issues, posted_issues)
        review_output.issues = deduped
        if not review_output.issues:
            review_output.summary = "没有新的高价值问题需要重复评论。"
            review_output.approval = "approve"
        posted_issues.extend(self.state_store.to_posted_issues(review_output.issues))
        self.state_store.save_state(
            ReviewState(pr_key=pr_key, reviewed_commits=reviewed_commits, posted_issues=posted_issues)
        )


def build_default_orchestrator(repo_root: Path | None = None) -> Orchestrator:
    """构建默认依赖图。"""

    root = (repo_root or Path.cwd()).resolve()
    settings = load_settings()
    github = GitHubClient(settings=settings)
    retriever = LocalRetriever(root)
    review = ReviewAgent(repo_root=root, github_client=github, retriever=retriever)
    cicd = CICDAgent(repo_root=root, github_client=github, retriever=retriever)
    fix = FixAgent(repo_root=root, github_client=github, retriever=retriever)
    state_store = ReviewStateStore(root / ".argus/state/review-state.json")
    return Orchestrator(
        review_agent=review,
        cicd_agent=cicd,
        github=github,
        state_store=state_store,
        fix_agent=fix,
        auto_fix=settings.argus_auto_fix,
        fix_confidence_threshold=settings.argus_fix_confidence_threshold,
    )
