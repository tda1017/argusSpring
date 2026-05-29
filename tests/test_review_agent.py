from pathlib import Path

from argus.agents.review import ReviewAgent
from argus.models import ReviewInput


async def test_review_agent_finds_security_issues(tmp_path) -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")
    agent = ReviewAgent(repo_root=tmp_path)

    output = await agent.run(ReviewInput(diff=diff, changed_files=["src/auth.py"]))

    assert output.approval == "request_changes"
    assert any(issue.category == "security" for issue in output.issues)
    assert any("SQL" in issue.description or "敏感信息" in issue.description for issue in output.issues)


async def test_review_agent_filters_suggestions_by_default(tmp_path) -> None:
    diff = """diff --git a/app.py b/app.py
--- a/app.py
+++ b/app.py
@@ -1 +1,2 @@
+print('debug')
"""
    agent = ReviewAgent(repo_root=tmp_path)

    output = await agent.run(ReviewInput(diff=diff, changed_files=["app.py"]))

    assert output.issues == []
    assert output.approval == "approve"
