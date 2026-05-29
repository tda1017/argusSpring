from pathlib import Path

from argus.agents.cicd import CICDAgent
from argus.models import CICDInput


async def test_cicd_agent_classifies_dependency_failure(tmp_path) -> None:
    log_text = Path("tests/fixtures/sample_logs/python_module_not_found.log").read_text(encoding="utf-8")
    agent = CICDAgent(repo_root=tmp_path)

    output = await agent.run(CICDInput(log_text=log_text))

    assert output.error_type == "dependency"
    assert "依赖" in output.fix_suggestion
    assert output.affected_files == ["/workspace/app/main.py"]


async def test_cicd_agent_classifies_js_compilation_failure(tmp_path) -> None:
    log_text = Path("tests/fixtures/sample_logs/js_build_failure.log").read_text(encoding="utf-8")
    agent = CICDAgent(repo_root=tmp_path)

    output = await agent.run(CICDInput(log_text=log_text))

    assert output.error_type == "compilation"
    assert any("TS2304" in line or "Build failed" in line for line in output.relevant_log_lines)
