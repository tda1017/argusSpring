package com.argus.review.application.port.out;

import reactor.core.publisher.Mono;

/**
 * 出站端口：GitHub 平台操作。
 */
public interface GitHubPort {

    /**
     * 获取 Pull Request 的 Diff 文本。
     *
     * @param owner      仓库所有者
     * @param repo       仓库名
     * @param prNumber   PR 编号
     * @return Diff 文本
     */
    Mono<String> fetchPrDiff(String owner, String repo, int prNumber);

    /**
     * 获取指定 Commit 的 Diff 文本。
     *
     * @param owner      仓库所有者
     * @param repo       仓库名
     * @param commitSha  Commit SHA
     * @return Diff 文本
     */
    Mono<String> fetchCommitDiff(String owner, String repo, String commitSha);

    /**
     * 在 Pull Request 下发表评论。
     *
     * @param owner      仓库所有者
     * @param repo       仓库名
     * @param prNumber   PR 编号
     * @param body       评论内容（支持 Markdown）
     * @return 创建成功的评论 ID
     */
    Mono<Long> postPrComment(String owner, String repo, int prNumber, String body);

}
