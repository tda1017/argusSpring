package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Function;

/**
 * 本包内部公共执行模板：统一计时、隔离异常、避免阻塞 WebFlux 线程。
 */
abstract class DiagnosticAgentSupport implements DiagnosisAgent {

    protected Mono<ToolResult> measured(DiagnosticContext context, Function<DiagnosticContext, String> query) {
        return Mono.fromCallable(() -> {
                long started = System.nanoTime();
                try {
                    String output = query.apply(context);
                    return result(true, output, started);
                } catch (Exception e) {
                    return result(false, e.getMessage(), started);
                }
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResult result(boolean success, String output, long started) {
        long latencyMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        return new ToolResult(name(), success, output, latencyMs);
    }
}
