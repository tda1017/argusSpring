"""CI 日志清洗与错误提取。"""

from __future__ import annotations

import re
from collections import Counter

from argus.models import ErrorTrace


ANSI_RE = re.compile(r"\x1B\[[0-?]*[ -/]*[@-~]")
TIMESTAMP_RE = re.compile(r"^(?:\d{4}-\d{2}-\d{2}[T ][^ ]+\s+|\[\d{2}:\d{2}:\d{2}\]\s+)")
PY_FILE_RE = re.compile(r'File "([^"]+)", line (\d+)')
JS_FILE_RE = re.compile(r"(?:\()?(.*?\.(?:js|jsx|ts|tsx)):(\d+)(?::\d+)?\)?")


def strip_ansi(text: str) -> str:
    """移除 ANSI 颜色码。"""

    return ANSI_RE.sub("", text)


def strip_timestamp(text: str) -> str:
    """移除常见时间戳前缀。"""

    return TIMESTAMP_RE.sub("", text)


def collapse_repeated(lines: list[str], threshold: int = 3) -> list[str]:
    """折叠大量重复日志。"""

    result: list[str] = []
    previous = None
    count = 0
    for line in lines:
        if line == previous:
            count += 1
            continue

        if previous is not None:
            result.append(previous)
            if count >= threshold:
                result.append(f"... repeated {count} times ...")
            elif count > 1:
                result.extend(previous for _ in range(count - 1))
        previous = line
        count = 1

    if previous is not None:
        result.append(previous)
        if count >= threshold:
            result.append(f"... repeated {count} times ...")
        elif count > 1:
            result.extend(previous for _ in range(count - 1))
    return result


def find_error_regions(lines: list[str], context: int = 10) -> list[tuple[int, int]]:
    """找到错误附近的日志区间。"""

    markers = ("error", "failed", "exception", "traceback", "fatal", "cannot find")
    regions: list[tuple[int, int]] = []
    for index, line in enumerate(lines):
        lowered = line.lower()
        if any(marker in lowered for marker in markers):
            regions.append((max(index - context, 0), min(index + context + 1, len(lines))))
    return regions


def merge_regions(lines: list[str], regions: list[tuple[int, int]]) -> list[str]:
    """合并重叠日志片段。"""

    if not regions:
        return []

    merged: list[tuple[int, int]] = []
    for start, end in sorted(regions):
        if not merged or start > merged[-1][1]:
            merged.append((start, end))
            continue
        merged[-1] = (merged[-1][0], max(merged[-1][1], end))

    selected: list[str] = []
    for start, end in merged:
        selected.extend(lines[start:end])
    return selected


def preprocess_log(raw_log: str, max_lines: int = 500) -> str:
    """将长日志裁剪成 LLM 更容易消费的版本。"""

    lines = [strip_timestamp(strip_ansi(line)) for line in raw_log.splitlines()]
    lines = [line for line in lines if line.strip()]
    lines = collapse_repeated(lines, threshold=3)
    regions = find_error_regions(lines, context=10)
    if regions:
        selected = merge_regions(lines, regions)
        if len(selected) <= max_lines:
            return "\n".join(selected)
        return "\n".join(selected[: max_lines - 1] + ["... (truncated) ..."])

    if len(lines) <= max_lines:
        return "\n".join(lines)
    head = lines[:50]
    tail = lines[-min(200, max_lines - 51) :]
    return "\n".join(head + ["... (truncated) ..."] + tail)


def detect_log_language(log_text: str) -> str:
    """根据日志关键词猜测语言生态。"""

    lowered = log_text.lower()
    scores = Counter()
    if "traceback" in lowered or "pytest" in lowered or "modulenotfounderror" in lowered:
        scores["python"] += 2
    if "npm err!" in lowered or "yarn" in lowered or "ts2304" in lowered:
        scores["javascript"] += 2
    if not scores:
        return "python"
    return scores.most_common(1)[0][0]


def parse_error_trace(log_text: str, language: str = "auto") -> list[ErrorTrace]:
    """解析 Python 和 JavaScript 的常见错误链。"""

    lang = detect_log_language(log_text) if language == "auto" else language
    if lang == "javascript":
        return _parse_javascript_trace(log_text)
    return _parse_python_trace(log_text)


def _parse_python_trace(log_text: str) -> list[ErrorTrace]:
    traces: list[ErrorTrace] = []
    pending_file: tuple[str, int] | None = None
    for raw_line in log_text.splitlines():
        line = strip_timestamp(strip_ansi(raw_line)).strip()
        file_match = PY_FILE_RE.search(line)
        if file_match:
            pending_file = (file_match.group(1), int(file_match.group(2)))
            continue

        exception_match = re.match(r"^([A-Za-z_][\w.]+(?:Error|Exception|Warning)):\s*(.+)$", line)
        if exception_match:
            file_path, line_number = pending_file or (None, None)
            traces.append(
                ErrorTrace(
                    type=exception_match.group(1),
                    message=exception_match.group(2),
                    file=file_path,
                    line=line_number,
                )
            )
            pending_file = None
            continue

        pytest_match = re.match(r"^E\s+(AssertionError|TypeError|ValueError|KeyError):\s*(.+)$", line)
        if pytest_match:
            file_path, line_number = pending_file or (None, None)
            traces.append(
                ErrorTrace(
                    type=pytest_match.group(1),
                    message=pytest_match.group(2),
                    file=file_path,
                    line=line_number,
                )
            )
            pending_file = None
    return traces


def _parse_javascript_trace(log_text: str) -> list[ErrorTrace]:
    traces: list[ErrorTrace] = []
    pending_file: tuple[str, int] | None = None
    for raw_line in log_text.splitlines():
        line = strip_timestamp(strip_ansi(raw_line)).strip()
        file_match = JS_FILE_RE.search(line)
        if file_match:
            pending_file = (file_match.group(1), int(file_match.group(2)))

        error_match = re.search(
            r"(?:npm ERR!\s+)?(?:error\s+)?([A-Za-z_][\w]+(?:Error|Exception)|TS\d+):\s*(.+)$",
            line,
            re.IGNORECASE,
        )
        if error_match:
            file_path, line_number = pending_file or (None, None)
            traces.append(
                ErrorTrace(
                    type=error_match.group(1).upper() if error_match.group(1).upper().startswith("TS") else error_match.group(1),
                    message=error_match.group(2),
                    file=file_path,
                    line=line_number,
                )
            )
            pending_file = None
    return traces
