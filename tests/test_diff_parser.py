from pathlib import Path

from argus.tools.diff_parser import chunk_diff, parse_unified_diff


def test_parse_unified_diff_keeps_new_line_numbers() -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")

    files = parse_unified_diff(diff)

    assert len(files) == 1
    assert files[0].path == "src/auth.py"
    assert files[0].hunks[0].added_lines[0].line_number == 11
    assert files[0].hunks[0].added_lines[1].content.startswith("    query = f")


def test_chunk_diff_returns_single_chunk_for_small_diff() -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")

    chunks = chunk_diff(diff, max_lines_per_chunk=100)

    assert len(chunks) == 1
    assert chunks[0].file == "src/auth.py"
