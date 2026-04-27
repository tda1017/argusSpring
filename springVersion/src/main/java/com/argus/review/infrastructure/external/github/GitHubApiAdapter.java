package com.argus.review.infrastructure.external.github;

import com.argus.review.application.port.out.GitHubPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * GitHub API 适配器（基于 WebClient）。
 */
@Slf4j
@Component
public class GitHubApiAdapter implements GitHubPort {

    private final WebClient webClient;

    /**
     * 初始化 GitHub WebClient，并在有 Token 时自动附加认证头。
     */
    public GitHubApiAdapter(
        @Value("${github.api.token:}") String apiToken,
        @Value("${github.api.base-url:https://api.github.com}") String baseUrl
    ) {
        WebClient.Builder builder = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json");

        if (apiToken != null && !apiToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + apiToken);
        }

        this.webClient = builder.build();
    }

    /**
     * 拉取 Pull Request 的原始 Diff。
     */
    @Override
    public Mono<String> fetchPrDiff(String owner, String repo, int prNumber) {
        log.info("[GitHub] Fetching PR diff: {}/{}/pulls/{}", owner, repo, prNumber);
        return webClient.get()
            .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
            .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
            .retrieve()
            .bodyToMono(String.class)
            .doOnNext(diff -> log.debug("[GitHub] Diff length: {} chars", diff.length()))
            .onErrorResume(e -> {
                log.error("[GitHub] Failed to fetch PR diff", e);
                return Mono.error(new RuntimeException("无法获取 PR Diff: " + e.getMessage(), e));
            });
    }

    /**
     * 拉取指定提交的原始 Diff。
     */
    @Override
    public Mono<String> fetchCommitDiff(String owner, String repo, String commitSha) {
        log.info("[GitHub] Fetching commit diff: {}/{}/commits/{}", owner, repo, commitSha);
        return webClient.get()
            .uri("/repos/{owner}/{repo}/commits/{commitSha}", owner, repo, commitSha)
            .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
            .retrieve()
            .bodyToMono(String.class)
            .doOnNext(diff -> log.debug("[GitHub] Commit diff length: {} chars", diff.length()))
            .onErrorResume(e -> {
                log.error("[GitHub] Failed to fetch commit diff", e);
                return Mono.error(new RuntimeException("无法获取 Commit Diff: " + e.getMessage(), e));
            });
    }

    /**
     * 在 PR 对应的 Issue 线程下发布评论。
     */
    @Override
    public Mono<Long> postPrComment(String owner, String repo, int prNumber, String body) {
        log.info("[GitHub] Posting comment to PR #{}", prNumber);
        return webClient.post()
            .uri("/repos/{owner}/{repo}/issues/{prNumber}/comments", owner, repo, prNumber)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new CommentRequest(body))
            .retrieve()
            .bodyToMono(CommentResponse.class)
            .map(CommentResponse::id)
            .doOnNext(id -> log.info("[GitHub] Comment posted, id={}", id))
            .onErrorResume(e -> {
                log.error("[GitHub] Failed to post comment", e);
                return Mono.error(new RuntimeException("无法发表评论: " + e.getMessage(), e));
            });
    }

    /**
     * GitHub 评论创建请求体。
     */
    record CommentRequest(String body) {}

    /**
     * GitHub 评论创建响应体。
     */
    record CommentResponse(Long id) {}

}
