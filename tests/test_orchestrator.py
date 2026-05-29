from pathlib import Path

from argus.agents.cicd import CICDAgent
from argus.agents.fix import FixAgent
from argus.agents.review import ReviewAgent
from argus.models import GitHubEvent
from argus.orchestrator import Orchestrator
from argus.state.review_state import ReviewStateStore


class FakeGitHubClient:
    def __init__(self, diff: str, file_content: str | None = None):
        self.diff = diff
        self.file_content = file_content or ""
        self.comments: list[str] = []

    async def get_pr_diff(self, repo: str, pr_number: int, installation_id: int = 0) -> str:
        return self.diff

    async def get_compare_diff(self, repo: str, base: str, head: str, installation_id: int = 0) -> str:
        return self.diff

    async def get_build_log(self, repo: str, run_id: int, installation_id: int = 0, job_name: str | None = None) -> str:
        return ""

    async def get_file_content(self, repo: str, path: str, ref: str | None = None, installation_id: int = 0) -> str:
        return self.file_content

    async def create_pr_comment(self, repo: str, pr_number: int, body: str, installation_id: int = 0) -> str:
        self.comments.append(body)
        return "https://example.test/comment/1"


async def test_orchestrator_deduplicates_incremental_review(tmp_path) -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")
    github = FakeGitHubClient(diff)
    review_agent = ReviewAgent(repo_root=tmp_path)
    cicd_agent = CICDAgent(repo_root=tmp_path)
    state_store = ReviewStateStore(tmp_path / "state/review-state.json")
    orchestrator = Orchestrator(review_agent=review_agent, cicd_agent=cicd_agent, github=github, state_store=state_store)

    first_event = GitHubEvent(type="pull_request", action="opened", repo="o/r", pr_number=1, installation_id=1, head_sha="sha1")
    second_event = GitHubEvent(type="pull_request", action="synchronize", repo="o/r", pr_number=1, installation_id=1, head_sha="sha2")

    first_result = await orchestrator.handle(first_event)
    second_result = await orchestrator.handle(second_event)

    assert "Code Review" in first_result.formatted_comment
    assert "没有新的高价值问题需要重复评论。" in second_result.formatted_comment
    state = state_store.get_state("o/r#1")
    assert state is not None
    assert state.reviewed_commits == ["sha1", "sha2"]


async def test_orchestrator_auto_fix_adds_fix_proposal(tmp_path) -> None:
    diff = Path("tests/fixtures/sample_diffs/security.diff").read_text(encoding="utf-8")
    source = '''def login(user_name):
    API_KEY = "super-secret"
    query = f"SELECT * FROM users WHERE name = '{user_name}'"
    cursor.execute(query)
    print("debug")
'''
    github = FakeGitHubClient(diff, file_content=source)
    review_agent = ReviewAgent(repo_root=tmp_path)
    cicd_agent = CICDAgent(repo_root=tmp_path)
    fix_agent = FixAgent(repo_root=tmp_path)
    state_store = ReviewStateStore(tmp_path / "state/review-state.json")
    orchestrator = Orchestrator(
        review_agent=review_agent,
        cicd_agent=cicd_agent,
        github=github,
        state_store=state_store,
        fix_agent=fix_agent,
        auto_fix=True,
        fix_confidence_threshold=0.8,
    )

    event = GitHubEvent(type="pull_request", action="opened", repo="o/r", pr_number=1, installation_id=1, head_sha="sha1")
    result = await orchestrator.handle(event)

    assert "Fix Proposal" in result.formatted_comment
    assert "API_KEY = os.environ[\"API_KEY\"]" in result.formatted_comment
