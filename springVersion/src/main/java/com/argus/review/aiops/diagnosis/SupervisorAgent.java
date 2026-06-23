package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosisEvent;
import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import com.argus.review.aiops.persistence.DiagnosisRecord;
import com.argus.review.aiops.persistence.DiagnosisRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 确定性调度器：并行跑工具 Agent，再串行执行根因、修复建议和持久化。
 */
@Service
@RequiredArgsConstructor
public class SupervisorAgent implements DiagnosisEngine {

    private final List<DiagnosisAgent> agents;
    private final RootCauseAnalyzer analyzer;
    private final DiagnosisRecordRepository repository;

    @Override
    public Flux<DiagnosisEvent> diagnose(AlertEvent alert) {
        DiagnosticContext context = new DiagnosticContext(alert);
        Flux<DiagnosisEvent> start = Flux.just(new DiagnosisEvent("start", "开始诊断: " + alert.alertName()));

        Flux<ToolResult> toolResults = Flux.mergeSequential(agents.stream()
            .map(agent -> agent.execute(context)
                .onErrorResume(e -> Mono.just(new ToolResult(agent.name(), false, e.getMessage(), 0L))))
            .toList());

        Mono<List<ToolResult>> collectedTools = toolResults.collectList()
            .doOnNext(context::addAllToolResults)
            .cache();

        Flux<DiagnosisEvent> toolEvents = collectedTools.flatMapMany(results -> Flux.fromIterable(results)
            .map(result -> new DiagnosisEvent("tool_result", result.agentName() + ": " + result.output())));

        Flux<DiagnosisEvent> analysisEvents = collectedTools.thenMany(Flux.concat(
            Mono.fromCallable(() -> analyzer.analyze(context))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(context::setRootCause)
                .map(rootCause -> new DiagnosisEvent("root_cause", rootCause))
                .flux(),
            Mono.fromCallable(() -> analyzer.suggestFix(context))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(context::setFixSuggestion)
                .map(fix -> new DiagnosisEvent("fix", fix))
                .flux(),
            Mono.defer(() -> repository.save(toRecord(context)))
                .thenReturn(new DiagnosisEvent("done", "诊断完成"))
                .flux()
        ));

        return Flux.concat(start, toolEvents, analysisEvents)
            .onErrorResume(e -> Flux.just(new DiagnosisEvent("error", e.getMessage())));
    }

    private DiagnosisRecord toRecord(DiagnosticContext context) {
        DiagnosisRecord record = new DiagnosisRecord();
        record.setDiagnosisId(context.diagnosisId());
        record.setAlert(context.alert());
        record.setToolResults(context.toolResults());
        record.setRootCause(context.rootCause());
        record.setFixSuggestion(context.fixSuggestion());
        record.setRelatedCaseIds(List.of());
        record.setCreatedAt(System.currentTimeMillis());
        record.setHumanVerified(false);
        return record;
    }
}
