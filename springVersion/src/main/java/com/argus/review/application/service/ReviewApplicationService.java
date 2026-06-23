package com.argus.review.application.service;

import com.argus.review.application.port.in.ReviewUseCase;
import com.argus.review.domain.agent.FixAgent;
import com.argus.review.domain.agent.AgentMessage;
import com.argus.review.domain.agent.AgentRole;
import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.agent.LogicAgent;
import com.argus.review.domain.agent.OrchestratorAgent;
import com.argus.review.domain.rag.RetrievalService;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 审查应用服务。
 * <p>编排多 Agent 协同、RAG 上下文检索与结果聚合。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewApplicationService implements ReviewUseCase {

    private final SecurityAgent securityAgent;
    private final StyleAgent styleAgent;
    private final LogicAgent logicAgent;
    private final FixAgent fixAgent;
    private final OrchestratorAgent orchestratorAgent;
    private final RetrievalService retrievalService;
    // 每次审查彼此独立，使用虚拟线程并行执行三个 Agent，减少等待时间。
    private final ExecutorService reviewExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步执行完整审查。
     *
     * @param codeDiff 代码变更内容
     * @return 聚合后的审查结果
     */
    @Override
    public ReviewResult review(String codeDiff) {
        String context = retrievalService.retrieveRelevantContext(codeDiff);
        return reviewWithContext(codeDiff, context);
    }

    /**
     * 用已有上下文执行三路并行审查。
     */
    private ReviewResult reviewWithContext(String codeDiff, String context) {
        // 三个维度互不依赖，直接并行跑，别写串行浪费时间。
        var securityFuture = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> securityAgent.review(codeDiff, context), reviewExecutor
        );
        var styleFuture = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> styleAgent.review(codeDiff, context), reviewExecutor
        );
        var logicFuture = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> logicAgent.review(codeDiff, context), reviewExecutor
        );

        java.util.concurrent.CompletableFuture.allOf(securityFuture, styleFuture, logicFuture).join();

        try {
            return new ReviewResult(
                securityFuture.get(),
                styleFuture.get(),
                logicFuture.get()
            );
        } catch (Exception e) {
            throw new RuntimeException("审查执行失败", e);
        }
    }

    /**
     * 流式执行完整审查。
     *
     * @param codeDiff 代码变更内容
     * @return 合并后的流式输出
     */
    @Override
    public Flux<String> reviewStream(String codeDiff) {
        String context = retrievalService.retrieveRelevantContext(codeDiff);

        Flux<String> securityFlux = tokenStreamToFlux("SECURITY", securityAgent.reviewStream(codeDiff, context));
        Flux<String> styleFlux = tokenStreamToFlux("STYLE", styleAgent.reviewStream(codeDiff, context));
        Flux<String> logicFlux = tokenStreamToFlux("LOGIC", logicAgent.reviewStream(codeDiff, context));

        // 直接合并三个输出流，保持标签，让前端自己决定展示顺序。
        return Flux.merge(securityFlux, styleFlux, logicFlux)
            .onErrorResume(e -> {
                log.error("流式审查异常", e);
                return Flux.just("[ERROR] " + e.getMessage());
            });
    }

    /**
     * 基于审查报告生成统一补丁。
     *
     * @param command 修复命令
     * @return unified diff patch
     */
    @Override
    public FixResult fix(FixCommand command) {
        String context = retrievalService.retrieveRelevantContext(command.codeDiff());
        String patch = fixAgent.generatePatch(
            blankToEmpty(command.projectId()),
            blankToEmpty(command.mrId()),
            command.codeDiff(),
            blankToEmpty(command.reviewReport()),
            context
        );
        return new FixResult(patch);
    }

    /**
     * 链式协同审查：审查先并行，安全问题再路由给 FixAgent。
     *
     * @param command 链式协同审查命令
     * @return 审查结果、修复补丁和消息路由记录
     */
    @Override
    public OrchestratedReviewResult reviewOrchestrated(OrchestratedReviewCommand command) {
        String context = retrievalService.retrieveRelevantContext(command.codeDiff());
        ReviewResult reviewResult = reviewWithContext(command.codeDiff(), context);
        List<AgentMessage> messages = new ArrayList<>();

        FixResult securityFix = orchestratorAgent.routeSecurityFindings(reviewResult.securityReport())
            .map(message -> {
                messages.add(message);
                String patch = fixAgent.generatePatch(
                    blankToEmpty(command.projectId()),
                    blankToEmpty(command.mrId()),
                    command.codeDiff(),
                    message.payload(),
                    context
                );
                messages.add(new AgentMessage(
                    AgentRole.FIX,
                    AgentRole.ORCHESTRATOR,
                    "SECURITY_FIX_READY",
                    patch
                ));
                return new FixResult(patch);
            })
            .orElseGet(() -> new FixResult(""));

        return new OrchestratedReviewResult(reviewResult, securityFix, List.copyOf(messages));
    }

    /**
     * 将 LangChain4j TokenStream 转成 Reactor Flux。
     */
    private Flux<String> tokenStreamToFlux(String label, TokenStream tokenStream) {
        if (tokenStream == null) {
            return Flux.empty();
        }
        return Flux.create(sink -> tokenStream
            .onPartialResponse(token -> sink.next("[" + label + "] " + token))
            .onCompleteResponse(response -> sink.complete())
            .onError(sink::error)
            .start());
    }

    /**
     * 应用关闭前释放审查线程池。
     */
    @PreDestroy
    void shutdownExecutor() {
        reviewExecutor.shutdown();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

}
