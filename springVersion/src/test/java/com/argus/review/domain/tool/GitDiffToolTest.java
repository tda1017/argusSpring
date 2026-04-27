package com.argus.review.domain.tool;

import com.argus.review.application.port.out.GitHubPort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GitDiffTool 参数解析测试。
 */
class GitDiffToolTest {

    private final StubGitHubPort gitHubPort = new StubGitHubPort();
    private final GitDiffTool gitDiffTool = new GitDiffTool(gitHubPort);

    /**
     * 仓库名 + PR 编号形式应被正确解析。
     */
    @Test
    void shouldResolveProjectAndPrNumber() {
        gitHubPort.prDiff = "diff-body";

        String diff = gitDiffTool.fetchMrDiff("openai/codex", "42");

        assertEquals("diff-body", diff);
    }

    /**
     * 完整 PR 链接形式应被正确解析。
     */
    @Test
    void shouldResolvePullRequestUrl() {
        gitHubPort.prDiff = "diff-url";

        String diff = gitDiffTool.fetchMrDiff("https://github.com/openai/codex/pull/42", "");

        assertEquals("diff-url", diff);
    }

    /**
     * 完整 Commit 链接形式应被正确解析。
     */
    @Test
    void shouldResolveCommitUrl() {
        gitHubPort.commitDiff = "commit-diff";

        String diff = gitDiffTool.fetchCommitDiff("https://github.com/openai/codex/commit/abc123", "");

        assertEquals("commit-diff", diff);
    }

    private static final class StubGitHubPort implements GitHubPort {

        private String prDiff = "";
        private String commitDiff = "";

        @Override
        public Mono<String> fetchPrDiff(String owner, String repo, int prNumber) {
            return Mono.just(prDiff);
        }

        @Override
        public Mono<String> fetchCommitDiff(String owner, String repo, String commitSha) {
            return Mono.just(commitDiff);
        }

        @Override
        public Mono<Long> postPrComment(String owner, String repo, int prNumber, String body) {
            return Mono.just(1L);
        }
    }

}
