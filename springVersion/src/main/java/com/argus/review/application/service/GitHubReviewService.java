package com.argus.review.application.service;

import com.argus.review.application.port.in.ReviewUseCase;
import com.argus.review.application.port.out.GitHubPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * GitHub PR 审查编排服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubReviewService {

    private final GitHubPort gitHubPort;
    private final ReviewUseCase reviewUseCase;

    /**
     * 对指定 PR 执行完整审查并回写评论。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber Pull Request 编号
     * @return GitHub 评论 ID
     */
    public Mono<Long> reviewPullRequest(String owner, String repo, int prNumber) {
        return gitHubPort.fetchPrDiff(owner, repo, prNumber)
            .publishOn(Schedulers.boundedElastic())
            .map(diff -> {
                log.info("[GitHubReview] PR #{} diff fetched ({} chars), starting review...", prNumber, diff.length());
                return reviewUseCase.review(diff);
            })
            .flatMap(result -> {
                // 统一拼装三类审查结果，避免控制器层关心评论格式细节。
                StringBuilder comment = new StringBuilder();
                comment.append("## 🔒 Argus AI 代码审查报告\n\n");

                comment.append("### 安全审查\n");
                comment.append(result.securityReport()).append("\n\n");

                comment.append("### 规范审查\n");
                comment.append(result.styleReport()).append("\n\n");

                comment.append("### 逻辑审查\n");
                comment.append(result.logicReport()).append("\n\n");

                comment.append("---\n");
                comment.append("*由 Argus AI Agent 自动生成*");

                return gitHubPort.postPrComment(owner, repo, prNumber, comment.toString());
            })
            .doOnSuccess(id -> log.info("[GitHubReview] Review completed for PR #{}, commentId={}", prNumber, id))
            .doOnError(e -> log.error("[GitHubReview] Review failed for PR #{}", prNumber, e));
    }

}
