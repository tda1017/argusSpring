package com.argus.review.application.service;

import com.argus.review.application.port.in.ReviewUseCase;
import dev.langchain4j.service.TokenStream;
import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.agent.LogicAgent;
import com.argus.review.domain.rag.RetrievalService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
     * 将 LangChain4j TokenStream 转成 Reactor Flux。
     */
    private Flux<String> tokenStreamToFlux(String label, TokenStream tokenStream) {
        if (tokenStream == null) {
            return Flux.empty();
        }
        return Flux.create(sink -> tokenStream
            .onNext(token -> sink.next("[" + label + "] " + token))
            .onComplete(response -> sink.complete())
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

}
