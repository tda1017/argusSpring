package com.argus.review.domain.tool;

import com.argus.review.application.port.out.GitHubPort;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Git 代码差异获取工具。
 * <p>当前接入 GitHub 真实 API，供 LLM 在需要时自主调用。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class GitDiffTool {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);

    private final GitHubPort gitHubPort;

    /**
     * 获取指定 Merge Request 的代码 Diff。
     *
     * @param projectId 项目 ID 或路径（如 "group/project"）
     * @param mrId      Merge Request IID
     * @return Diff 文本内容
     */
    @Tool("从 GitLab/GitHub 获取指定 MR 的代码 Diff。当用户仅提供 MR 链接或 ID 时使用此工具。")
    public String fetchMrDiff(String projectId, String mrId) {
        PullRequestTarget target = resolvePullRequestTarget(projectId, mrId);
        log.info("[Tool] 正在获取 GitHub PR Diff: {}/{}#{}", target.owner(), target.repo(), target.prNumber());

        return Objects.requireNonNull(
            gitHubPort.fetchPrDiff(target.owner(), target.repo(), target.prNumber())
                .block(API_TIMEOUT),
            "GitHub PR Diff 返回为空"
        );
    }

    /**
     * 获取指定 Commit SHA 的代码变更。
     *
     * @param projectId  项目 ID 或路径
     * @param commitSha  Commit SHA
     * @return Diff 文本内容
     */
    @Tool("获取指定 Commit 的代码 Diff。当需要审查某个特定提交时使用此工具。")
    public String fetchCommitDiff(String projectId, String commitSha) {
        CommitTarget target = resolveCommitTarget(projectId, commitSha);
        log.info("[Tool] 正在获取 GitHub Commit Diff: {}/{}/{}", target.owner(), target.repo(), target.commitSha());

        return Objects.requireNonNull(
            gitHubPort.fetchCommitDiff(target.owner(), target.repo(), target.commitSha())
                .block(API_TIMEOUT),
            "GitHub Commit Diff 返回为空"
        );
    }

    /**
     * 统一解析 PR 输入，兼容仓库名 + 编号、完整 URL 两种形式。
     */
    private PullRequestTarget resolvePullRequestTarget(String projectId, String mrId) {
        if (isGitHubUrl(mrId)) {
            return parsePullRequestUrl(mrId);
        }
        if (isGitHubUrl(projectId)) {
            PullRequestTarget fromUrl = parsePullRequestUrl(projectId);
            if (mrId == null || mrId.isBlank()) {
                return fromUrl;
            }
            return new PullRequestTarget(fromUrl.owner(), fromUrl.repo(), parsePrNumber(mrId));
        }
        RepositoryTarget repositoryTarget = parseRepository(projectId);
        return new PullRequestTarget(
            repositoryTarget.owner(),
            repositoryTarget.repo(),
            parsePrNumber(mrId)
        );
    }

    /**
     * 统一解析 Commit 输入，兼容仓库名 + SHA、完整 URL 两种形式。
     */
    private CommitTarget resolveCommitTarget(String projectId, String commitSha) {
        if (isGitHubUrl(commitSha)) {
            return parseCommitUrl(commitSha);
        }
        if (isGitHubUrl(projectId)) {
            CommitTarget fromUrl = parseCommitUrl(projectId);
            if (commitSha == null || commitSha.isBlank()) {
                return fromUrl;
            }
            return new CommitTarget(fromUrl.owner(), fromUrl.repo(), commitSha.trim());
        }
        RepositoryTarget repositoryTarget = parseRepository(projectId);
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("commitSha 不能为空");
        }
        return new CommitTarget(repositoryTarget.owner(), repositoryTarget.repo(), commitSha.trim());
    }

    /**
     * 判断输入值是否为 GitHub 链接。
     */
    private boolean isGitHubUrl(String value) {
        return value != null && value.contains("github.com/");
    }

    /**
     * 解析 `owner/repo` 形式的仓库标识。
     */
    private RepositoryTarget parseRepository(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空，格式应为 owner/repo");
        }
        String normalized = projectId.trim();
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String[] parts = normalized.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("projectId 格式错误，应为 owner/repo");
        }
        return new RepositoryTarget(parts[0].trim(), parts[1].trim());
    }

    /**
     * 从 GitHub PR 链接中提取仓库与 PR 编号。
     */
    private PullRequestTarget parsePullRequestUrl(String url) {
        String[] parts = extractGitHubPathParts(url);
        if (parts.length < 4 || !"pull".equals(parts[2])) {
            throw new IllegalArgumentException("暂不支持的 PR URL: " + url);
        }
        return new PullRequestTarget(parts[0], parts[1], parsePrNumber(parts[3]));
    }

    /**
     * 从 GitHub Commit 链接中提取仓库与提交 SHA。
     */
    private CommitTarget parseCommitUrl(String url) {
        String[] parts = extractGitHubPathParts(url);
        if (parts.length < 4 || !"commit".equals(parts[2])) {
            throw new IllegalArgumentException("暂不支持的 Commit URL: " + url);
        }
        return new CommitTarget(parts[0], parts[1], parts[3]);
    }

    /**
     * 提取 GitHub URL 的路径片段，过滤掉空段。
     */
    private String[] extractGitHubPathParts(String url) {
        URI uri = URI.create(url.trim());
        String[] rawParts = uri.getPath().split("/");
        return java.util.Arrays.stream(rawParts)
            .filter(part -> !part.isBlank())
            .toArray(String[]::new);
    }

    /**
     * 解析 PR 编号，输入必须是数字字符串。
     */
    private int parsePrNumber(String mrId) {
        if (mrId == null || mrId.isBlank()) {
            throw new IllegalArgumentException("mrId 不能为空");
        }
        try {
            return Integer.parseInt(mrId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("mrId 必须是数字或 GitHub PR URL", e);
        }
    }

    private record RepositoryTarget(String owner, String repo) {}

    private record PullRequestTarget(String owner, String repo, int prNumber) {}

    private record CommitTarget(String owner, String repo, String commitSha) {}

}
