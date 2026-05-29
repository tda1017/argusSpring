package com.argus.review.application.port.in;

import com.argus.review.domain.agent.AgentMessage;
import reactor.core.publisher.Flux;

import java.util.List;

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
     * 基于审查结果生成可应用的修复补丁。
     */
    FixResult fix(FixCommand command);

    /**
     * 执行链式协同审查：审查 Agent 先跑，Orchestrator 再把问题路由给 FixAgent。
     */
    OrchestratedReviewResult reviewOrchestrated(OrchestratedReviewCommand command);

    /**
     * 审查结果 DTO。
     */
    record ReviewResult(
        String securityReport,
        String styleReport,
        String logicReport
    ) {}

    /**
     * 修复请求命令。
     */
    record FixCommand(
        String projectId,
        String mrId,
        String codeDiff,
        String reviewReport
    ) {}

    /**
     * 修复结果 DTO。
     */
    record FixResult(String patch) {}

    /**
     * 链式协同审查请求命令。
     */
    record OrchestratedReviewCommand(
        String projectId,
        String mrId,
        String codeDiff
    ) {}

    /**
     * 链式协同审查结果。
     */
    record OrchestratedReviewResult(
        ReviewResult reviewResult,
        FixResult securityFix,
        List<AgentMessage> messages
    ) {}

}
