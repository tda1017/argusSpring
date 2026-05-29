from pathlib import Path

from click.testing import CliRunner

from argus.cli import cli


def test_cli_review_command_reads_diff_file() -> None:
    runner = CliRunner()
    diff_file = Path("tests/fixtures/sample_diffs/security.diff")

    result = runner.invoke(cli, ["review", "--diff-file", str(diff_file)])

    assert result.exit_code == 0
    assert "Argus Code Review" in result.output
    assert "request_changes" in result.output



def test_cli_without_command_starts_repl(monkeypatch) -> None:
    called = {"ok": False}

    async def fake_start_repl() -> None:
        called["ok"] = True

    monkeypatch.setattr("argus.cli.start_repl", fake_start_repl)

    runner = CliRunner()
    result = runner.invoke(cli, [])

    assert result.exit_code == 0
    assert called["ok"] is True
