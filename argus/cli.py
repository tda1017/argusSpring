"""Argus CLI。"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path

import click

from argus.agents.cicd import CICDAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.config import ProviderConfig, environment_provider_payload, save_global_provider_config
from argus.github import GitHubClient
from argus.models import CICDInput, CICDOutput, FixInput, ReviewInput, ReviewOutput
from argus.patching import apply_file_patches, render_patch_diff
from argus.project_rules import find_project_rules
from argus.rag import LocalRetriever
from argus.repl.session import start_repl
from argus.tools.diff_parser import changed_files


@click.group(invoke_without_command=True)
@click.option("--provider", default="", help="Temporary provider override")
@click.option("--model", default="", help="Temporary model override")
@click.option("--base-url", default="", help="Temporary base URL override")
@click.pass_context
def cli(ctx: click.Context, provider: str, model: str, base_url: str) -> None:
    """Argus - Interactive AI Code Review & CI/CD Assistant"""

    if provider:
        os.environ["ARGUS_PROVIDER"] = provider
    if model:
        os.environ["ARGUS_MODEL"] = model
    if base_url:
        os.environ["ARGUS_BASE_URL"] = base_url

    if ctx.invoked_subcommand is None:
        asyncio.run(start_repl())


@cli.command()
@click.option("--repo", help="GitHub repo (owner/repo)")
@click.option("--pr", "pr_number", type=int, help="PR number")
@click.option("--diff-file", type=click.Path(exists=True, dir_okay=False, path_type=Path), help="Local diff file")
@click.option("--title", default="", help="PR title")
@click.option("--body", default="", help="PR body")
@click.option("--verbose", is_flag=True, help="Show trace output")
def review(repo: str | None, pr_number: int | None, diff_file: Path | None, title: str, body: str, verbose: bool) -> None:
    """审查代码变更。"""

    asyncio.run(_review(repo, pr_number, diff_file, title, body, verbose))


async def _review(repo: str | None, pr_number: int | None, diff_file: Path | None, title: str, body: str, verbose: bool) -> None:
    repo_root = Path.cwd()
    github = GitHubClient()
    retriever = LocalRetriever(repo_root)
    agent = ReviewAgent(repo_root=repo_root, github_client=github, retriever=retriever)

    if diff_file:
        diff = diff_file.read_text(encoding="utf-8")
    elif repo and pr_number:
        diff = await github.get_pr_diff(repo, pr_number)
    else:
        diff = click.get_text_stream("stdin").read()

    result = await agent.run(
        ReviewInput(
            repo=repo,
            pr_number=pr_number,
            diff=diff,
            changed_files=changed_files(diff),
            pr_title=title,
            pr_body=body,
            repo_root=str(repo_root),
        )
    )
    click.echo("🔍 Argus Code Review\n━━━━━━━━━━━━━━━━━━━━\n")
    click.echo(result.to_markdown())
    if verbose and agent.last_trace:
        click.echo("\n" + agent.last_trace.to_summary(agent.model_name))
    await github.close()


@cli.command()
@click.option("--repo", help="GitHub repo")
@click.option("--run", "run_id", type=int, help="GitHub Actions run ID")
@click.option("--log-file", type=click.Path(exists=True, dir_okay=False, path_type=Path), help="Local log file")
@click.option("--verbose", is_flag=True, help="Show trace output")
def diagnose(repo: str | None, run_id: int | None, log_file: Path | None, verbose: bool) -> None:
    """分析 CI 构建失败。"""

    asyncio.run(_diagnose(repo, run_id, log_file, verbose))


async def _diagnose(repo: str | None, run_id: int | None, log_file: Path | None, verbose: bool) -> None:
    repo_root = Path.cwd()
    github = GitHubClient()
    retriever = LocalRetriever(repo_root)
    agent = CICDAgent(repo_root=repo_root, github_client=github, retriever=retriever)

    if log_file:
        log_text = log_file.read_text(encoding="utf-8")
    elif repo and run_id:
        log_text = await github.get_build_log(repo, run_id)
    else:
        log_text = click.get_text_stream("stdin").read()

    result = await agent.run(CICDInput(repo=repo, run_id=run_id, log_text=log_text, repo_root=str(repo_root)))
    click.echo("🔧 Argus CI/CD Diagnosis\n━━━━━━━━━━━━━━━━━━━━\n")
    click.echo(result.to_markdown())
    if verbose and agent.last_trace:
        click.echo("\n" + agent.last_trace.to_summary(agent.model_name))
    await github.close()


@cli.command()
@click.option("--diff-file", type=click.Path(exists=True, dir_okay=False, path_type=Path), help="Diff file to fix")
@click.option("--diagnosis", type=click.Path(exists=True, dir_okay=False, path_type=Path), help="Diagnosis JSON file")
@click.option("--apply", "apply_changes", is_flag=True, help="Apply generated patches")
@click.option("--verbose", is_flag=True, help="Show trace output")
def fix(diff_file: Path | None, diagnosis: Path | None, apply_changes: bool, verbose: bool) -> None:
    """基于诊断结果生成修复补丁。"""

    asyncio.run(_fix(diff_file, diagnosis, apply_changes, verbose))


async def _fix(diff_file: Path | None, diagnosis: Path | None, apply_changes: bool, verbose: bool) -> None:
    repo_root = Path.cwd()
    github = GitHubClient()
    retriever = LocalRetriever(repo_root)
    review_agent = ReviewAgent(repo_root=repo_root, github_client=github, retriever=retriever)
    fix_agent = FixAgent(repo_root=repo_root, github_client=github, retriever=retriever)

    diagnosis_obj: ReviewOutput | CICDOutput
    if diagnosis:
        payload = json.loads(diagnosis.read_text(encoding="utf-8"))
        diagnosis_obj = ReviewOutput.model_validate(payload) if "issues" in payload else CICDOutput.model_validate(payload)
    elif diff_file:
        diff = diff_file.read_text(encoding="utf-8")
        diagnosis_obj = await review_agent.run(ReviewInput(diff=diff, changed_files=changed_files(diff), repo_root=str(repo_root)))
    else:
        payload = json.loads(click.get_text_stream("stdin").read())
        diagnosis_obj = ReviewOutput.model_validate(payload) if "issues" in payload else CICDOutput.model_validate(payload)

    file_paths = sorted({issue.file for issue in diagnosis_obj.issues}) if isinstance(diagnosis_obj, ReviewOutput) else sorted(set(diagnosis_obj.affected_files))
    file_contents = {}
    for path in file_paths:
        target = repo_root / path
        if target.exists():
            file_contents[path] = target.read_text(encoding="utf-8")

    result = await fix_agent.run(
        FixInput(
            diagnosis=diagnosis_obj,
            file_contents=file_contents,
            repo_root=str(repo_root),
            rules=find_project_rules(repo_root),
        )
    )
    click.echo("🩹 Argus Fix Preview\n━━━━━━━━━━━━━━━━━━━━\n")
    click.echo(result.to_markdown())
    for patch in result.patches:
        click.echo(f"\n{render_patch_diff(patch)}\n")

    if apply_changes and result.patches:
        apply_result = apply_file_patches(repo_root, result.patches, dry_run=False)
        click.echo(f"Applied: {apply_result.applied}")
        if apply_result.failed:
            click.echo(f"Failed: {apply_result.failed}")

    if verbose and fix_agent.last_trace:
        click.echo("\n" + fix_agent.last_trace.to_summary(fix_agent.model_name))
    await github.close()


@cli.command()
@click.option("--path", "target_path", type=click.Path(exists=True, file_okay=False, path_type=Path), default=Path.cwd())
def index(target_path: Path) -> None:
    """为本地仓库建立检索索引。"""

    retriever = LocalRetriever(target_path)
    count = retriever.index_repository(target_path)
    click.echo(f"Indexed {count} chunks into `{retriever.index_file}`")


@cli.command()
def init() -> None:
    """在当前目录创建 argus.md 模板。"""

    template = """# Argus 项目规范

## 代码规范
- 使用明确的类型和错误处理

## 审查重点
- 检查安全问题、错误处理和性能回退

## 忽略规则
- 忽略 generated/ 和第三方产物

## CI/CD 上下文
- 写清构建命令、测试命令和常见失败原因

## 审查噪音控制
- max_comments: 5
- severity_threshold: warning
- ignore_categories: [style]
"""
    Path("argus.md").write_text(template, encoding="utf-8")
    click.echo("Created `argus.md`.")


@cli.command()
def config() -> None:
    """交互式配置默认 Provider。"""

    provider = click.prompt("Provider", default="openai")
    api_type = "anthropic" if provider == "anthropic" else "openai-compatible"
    api_key = click.prompt("API key", hide_input=True)
    base_url = click.prompt("Base URL", default="" if provider == "anthropic" else "https://api.openai.com/v1")
    model = click.prompt("Model", default="claude-sonnet-4-20250514" if provider == "anthropic" else "gpt-4o-mini")
    path = save_global_provider_config(
        ProviderConfig(provider=provider, api_type=api_type, api_key=api_key, base_url=base_url, model=model)
    )
    click.echo(f"Saved config to `{path}`")


@cli.command()
@click.option("--port", default=8000, show_default=True, type=int)
def serve(port: int) -> None:
    """启动 Webhook 服务。"""

    import uvicorn

    uvicorn.run("argus.main:app", host="0.0.0.0", port=port, reload=False)


@cli.command("env")
def show_env() -> None:
    """显示当前环境覆盖配置。"""

    payload = environment_provider_payload()
    if not payload:
        click.echo("No ARGUS_* environment overrides.")
        return
    for key, value in payload.items():
        click.echo(f"{key}={value}")
