"""Token 成本估算。"""

from __future__ import annotations


TOKEN_COSTS = {
    "claude-sonnet-4-20250514": {"input": 3.0 / 1_000_000, "output": 15.0 / 1_000_000},
    "claude-opus-4-20250514": {"input": 15.0 / 1_000_000, "output": 75.0 / 1_000_000},
    "gpt-4o": {"input": 2.5 / 1_000_000, "output": 10.0 / 1_000_000},
    "gpt-4o-mini": {"input": 0.15 / 1_000_000, "output": 0.60 / 1_000_000},
}


def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float:
    """估算一次调用成本。"""

    costs = TOKEN_COSTS.get(model, {"input": 0.0, "output": 0.0})
    return round(input_tokens * costs["input"] + output_tokens * costs["output"], 6)
