from pathlib import Path

from argus.tools.log_parser import parse_error_trace, preprocess_log


def test_preprocess_log_strips_timestamps() -> None:
    raw = Path("tests/fixtures/sample_logs/python_module_not_found.log").read_text(encoding="utf-8")

    processed = preprocess_log(raw)

    assert "2026-03-10T10:00:01Z" not in processed
    assert "ModuleNotFoundError" in processed


def test_parse_python_error_trace() -> None:
    raw = Path("tests/fixtures/sample_logs/python_module_not_found.log").read_text(encoding="utf-8")

    traces = parse_error_trace(raw, language="python")

    assert traces[0].type == "ModuleNotFoundError"
    assert traces[0].file == "/workspace/app/main.py"
    assert traces[0].line == 12


def test_parse_javascript_error_trace() -> None:
    raw = Path("tests/fixtures/sample_logs/js_build_failure.log").read_text(encoding="utf-8")

    traces = parse_error_trace(raw, language="javascript")

    assert traces[0].type == "TS2304"
    assert traces[0].file == "src/app.ts"
