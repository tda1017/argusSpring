from argus.models import ReviewIssue
from argus.state.review_state import ReviewStateStore


def test_review_state_store_deduplicates_issues(tmp_path) -> None:
    store = ReviewStateStore(tmp_path / "review-state.json")
    issue = ReviewIssue(
        file="src/auth.py",
        line=10,
        severity="critical",
        category="security",
        description="疑似把敏感信息硬编码进代码。",
        suggestion="改用环境变量。",
        code_snippet=None,
    )

    posted = store.to_posted_issues([issue])
    deduped = store.deduplicate([issue], posted)

    assert deduped == []
