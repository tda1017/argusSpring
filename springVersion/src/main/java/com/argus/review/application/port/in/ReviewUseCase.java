package com.argus.review.application.port.in;

import reactor.core.publisher.Flux;

/**
 * 入站端口：代码审查用例。
 */
public interface ReviewUseCase {

    /**
     * 执行多维度代码审查（同步聚合结果）。
     */
    ReviewResult review(String codeDiff);

    /**
     * 流式执行代码审查，逐 Token 返回 SSE。
     */
    Flux<String> reviewStream(String codeDiff);

    /**
     * 审查结果 DTO。
     */
    record ReviewResult(
        String securityReport,
        String styleReport,
        String logicReport
    ) {}

}
