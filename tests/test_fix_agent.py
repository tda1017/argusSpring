from pathlib import Path

from argus.agents.fix import FixAgent
from argus.models import FixInput, ReviewIssue, ReviewOutput
from argus.patching import apply_file_patches


async def test_fix_agent_generates_patch_for_secret_sql_and_debug(tmp_path) -> None:
    source = tmp_path / "src" / "auth.py"
    source.parent.mkdir(parents=True)
    content = '''def login(user_name):
    API_KEY = "super-secret"
    query = f"SELECT * FROM users WHERE name = '{user_name}'"
    cursor.execute(query)
    print("debug")
'''
    source.write_text(content, encoding="utf-8")

    diagnosis = ReviewOutput(
        summary="发现严重问题",
        approval="request_changes",
        issues=[
            ReviewIssue(
                file="src/auth.py",
                line=2,
                severity="critical",
                category="security",
                description="疑似把敏感信息硬编码进代码。",
                suggestion="改用环境变量。",
                code_snippet='API_KEY = "super-secret"',
            ),
            ReviewIssue(
                file="src/auth.py",
                line=3,
                severity="critical",
                category="security",
                description="SQL 语句看起来是字符串拼接，容易引入注入风险。",
                suggestion="使用参数化查询。",
                code_snippet="cursor.execute(query)",
            ),
        ],
    )

    agent = FixAgent(repo_root=tmp_path)
    output = await agent.run(
        FixInput(
            diagnosis=diagnosis,
            file_contents={"src/auth.py": content},
            repo_root=str(tmp_path),
        )
    )

    assert len(output.patches) == 1
    patch = output.patches[0]
    assert 'os.environ["API_KEY"]' in patch.patched
    assert 'cursor.execute("SELECT * FROM users WHERE name = %s", (user_name,))' in patch.patched
    assert 'print("debug")' not in patch.patched


async def test_apply_file_patches_writes_changes(tmp_path) -> None:
    source = tmp_path / "src" / "auth.py"
    source.parent.mkdir(parents=True)
    source.write_text('API_KEY = "super-secret"\n', encoding="utf-8")

    diagnosis = ReviewOutput(
        summary="发现严重问题",
        approval="request_changes",
        issues=[
            ReviewIssue(
                file="src/auth.py",
                line=1,
                severity="critical",
                category="security",
                description="疑似把敏感信息硬编码进代码。",
                suggestion="改用环境变量。",
                code_snippet='API_KEY = "super-secret"',
            )
        ],
    )
    agent = FixAgent(repo_root=tmp_path)
    output = await agent.run(
        FixInput(diagnosis=diagnosis, file_contents={"src/auth.py": source.read_text(encoding="utf-8")}, repo_root=str(tmp_path))
    )

    result = apply_file_patches(tmp_path, output.patches, dry_run=False)

    assert result.applied == ["src/auth.py"]
    assert 'os.environ["API_KEY"]' in source.read_text(encoding="utf-8")
