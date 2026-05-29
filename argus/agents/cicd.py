"""CI/CD Agent 实现。"""

from __future__ import annotations

import re
from pathlib import Path

from argus.agents.base import BaseAgent
from argus.github import GitHubClient, read_local_file
from argus.models import CICDInput, CICDOutput, ErrorTrace
from argus.rag import LocalRetriever
from argus.tooling import ToolDefinition
from argus.tools.log_parser import parse_error_trace, preprocess_log


CICD_SYSTEM_PROMPT = """You are Argus CI/CD Agent. Analyze build failures, identify the root cause, and return valid JSON only.
Focus on the earliest actionable error, avoid repeating noisy downstream failures, and propose a concrete fix.
"""


class CICDAgent(BaseAgent[CICDInput, CICDOutput]):
    """CI 构建失败诊断 Agent。"""

    name = "cicd"
    system_prompt = CICD_SYSTEM_PROMPT

    def __init__(self, provider=None, repo_root: Path | None = None, github_client: GitHubClient | None = None, retriever: LocalRetriever | None = None):
        self.github = github_client
        self.retriever = retriever
        super().__init__(provider=provider, repo_root=repo_root)

    @property
    def output_model(self) -> type[CICDOutput]:
        return CICDOutput

    def register_tools(self) -> None:
        self.tools.register(
            ToolDefinition(
                name="get_build_log",
                description="获取 GitHub Actions 构建日志",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": "string"},
                        "run_id": {"type": "integer"},
                        "job_name": {"type": ["string", "null"]},
                    },
                    "required": ["repo", "run_id"],
                },
                handler=self.get_build_log,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="parse_error_trace",
                description="解析日志中的结构化错误链",
                parameters={
                    "type": "object",
                    "properties": {
                        "log_text": {"type": "string"},
                        "language": {"type": "string", "default": "auto"},
                    },
                    "required": ["log_text"],
                },
                handler=parse_error_trace,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="search_solutions",
                description="搜索历史上类似的 CI 失败和解决方案",
                parameters={
                    "type": "object",
                    "properties": {
                        "error_message": {"type": "string"},
                        "top_k": {"type": "integer", "default": 3},
                    },
                    "required": ["error_message"],
                },
                handler=self.search_solutions,
            )
        )
        self.tools.register(
            ToolDefinition(
                name="get_file_content",
                description="读取构建失败涉及的文件内容",
                parameters={
                    "type": "object",
                    "properties": {
                        "repo": {"type": "string"},
                        "path": {"type": "string"},
                        "ref": {"type": "string"},
                    },
                    "required": ["path"],
                },
                handler=self.get_file_content,
            )
        )

    def build_user_prompt(self, input_data: CICDInput) -> str:
        log_text = preprocess_log(input_data.log_text or "")
        traces = parse_error_trace(log_text, language="auto")
        schema = CICDOutput.model_json_schema()
        return (
            f"Analyze this CI/CD failure and return JSON matching this schema:\n{schema}\n\n"
            f"Run URL: {input_data.run_url or '(none)'}\n"
            f"Conclusion: {input_data.conclusion}\n"
            f"Error traces: {[item.model_dump() for item in traces]}\n\n"
            f"Processed log:\n{log_text}"
        )

    async def fallback(self, input_data: CICDInput) -> CICDOutput:
        log_text = input_data.log_text or ""
        processed = preprocess_log(log_text)
        traces = parse_error_trace(processed, language="auto")
        primary = traces[0] if traces else self._build_trace_from_log(processed)

        if primary is None:
            return CICDOutput(
                root_cause="日志里没有抓到明确错误，只能确认构建失败。",
                error_type="config",
                relevant_log_lines=processed.splitlines()[-5:],
                affected_files=[],
                fix_suggestion="先复现失败步骤，再查看失败 job 的完整原始日志。",
                fix_confidence="low",
                debugging_steps=["重新运行失败 job", "开启更详细的构建日志", "确认最近变更的配置文件"],
            )

        error_type = self._classify(primary, processed, input_data.conclusion)
        fix_suggestion = self._fix_suggestion(primary, error_type)
        relevant = self._relevant_lines(processed, primary)
        confidence = "high" if primary.type.lower().endswith("error") else "medium"
        if error_type in {"timeout", "resource"}:
            confidence = "medium"

        debugging_steps = []
        if confidence == "low":
            debugging_steps = ["定位第一条报错", "检查最近变更的依赖或配置", "在本地复现同样命令"]
        return CICDOutput(
            root_cause=f"最早可执行错误是 {primary.type}: {primary.message}",
            error_type=error_type,
            relevant_log_lines=relevant,
            affected_files=[primary.file] if primary.file else [],
            fix_suggestion=fix_suggestion,
            fix_confidence=confidence,
            debugging_steps=debugging_steps,
        )

    async def get_build_log(self, repo: str, run_id: int, job_name: str | None = None) -> str:
        if not self.github:
            return ""
        return await self.github.get_build_log(repo, run_id, job_name=job_name)

    def search_solutions(self, error_message: str, top_k: int = 3) -> list[dict[str, str]]:
        if not self.retriever:
            return []
        return self.retriever.search_solutions(error_message, top_k=top_k)

    async def get_file_content(self, path: str, repo: str | None = None, ref: str | None = None) -> str:
        if repo and self.github:
            return await self.github.get_file_content(repo, path, ref=ref)
        return await read_local_file(self.repo_root, path)

    def _build_trace_from_log(self, processed_log: str) -> ErrorTrace | None:
        lines = processed_log.splitlines()
        for line in lines:
            lowered = line.lower()
            if "timed out" in lowered:
                return ErrorTrace(type="TimeoutError", message=line)
            if "out of memory" in lowered or line.strip() == "Killed":
                return ErrorTrace(type="ResourceError", message=line)
        return None

    def _classify(self, trace: ErrorTrace, log_text: str, conclusion: str) -> str:
        lowered_message = trace.message.lower()
        lowered_log = log_text.lower()
        if "timeout" in lowered_message or conclusion == "timed_out":
            return "timeout"
        if "out of memory" in lowered_message or "killed" in lowered_log:
            return "resource"
        if any(token in lowered_message for token in ["module not found", "cannot find module", "no module named"]):
            return "dependency"
        if any(token in lowered_message for token in ["assertion", "expected", "received", "failed"]):
            return "test"
        if any(token in lowered_message for token in ["config", "unknown option", "invalid configuration"]):
            return "config"
        return "compilation"

    def _fix_suggestion(self, trace: ErrorTrace, error_type: str) -> str:
        message = trace.message.lower()
        if error_type == "dependency":
            return "补齐缺失依赖并确认锁文件已更新，然后重新执行安装和构建。"
        if error_type == "test":
            return "先修复失败断言或测试夹具，再本地跑同一条测试命令确认稳定。"
        if error_type == "timeout":
            return "缩短单次任务耗时，或把慢任务拆分后再评估 CI 超时阈值。"
        if error_type == "resource":
            return "检查是否有异常占用内存/CPU 的步骤，必要时给 job 增加资源或拆分任务。"
        if "ts" in trace.type.lower() or "syntax" in message:
            return "修复语法或类型错误，并在本地先跑编译命令。"
        return "从第一条错误入手修复源码或配置，再本地复现完整构建。"

    def _relevant_lines(self, processed_log: str, trace: ErrorTrace) -> list[str]:
        results = []
        for line in processed_log.splitlines():
            if trace.type in line or trace.message in line:
                results.append(line)
            if len(results) >= 5:
                break
        return results or processed_log.splitlines()[-5:]
