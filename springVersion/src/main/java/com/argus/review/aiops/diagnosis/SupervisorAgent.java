package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.knowledge.KnowledgeSearchService;
import com.argus.review.aiops.memory.MemoryService;
import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosisEvent;
import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.KnowledgeChunk;
import com.argus.review.aiops.model.ToolResult;
import com.argus.review.aiops.persistence.DiagnosisRecord;
import com.argus.review.aiops.persistence.DiagnosisRecordRepository;
import com.argus.review.aiops.remediation.RemediationService;
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
    private final KnowledgeSearchService knowledgeSearchService;
    private final MemoryService memoryService;
    private final RemediationService remediationService;
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
            memoryService.profile(alert)
                .doOnNext(context::setServiceProfile)
                .map(profile -> new DiagnosisEvent("memory", profile))
                .flux(),
            Mono.fromCallable(() -> knowledgeSearchService.search(alert.description(), 3))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(context::addAllKnowledge)
                .map(chunks -> new DiagnosisEvent("knowledge", "检索到 " + chunks.size() + " 条知识: " + sources(chunks)))
                .flux(),
            Mono.fromCallable(() -> analyzer.analyze(context))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(fallbackRootCause(context))
                .doOnNext(context::setRootCause)
                .map(rootCause -> new DiagnosisEvent("root_cause", rootCause))
                .flux(),
            Mono.fromCallable(() -> analyzer.suggestFix(context))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(fallbackFix(context))
                .doOnNext(context::setFixSuggestion)
                .map(fix -> new DiagnosisEvent("fix", fix))
                .flux(),
            Mono.defer(() -> repository.save(toRecord(context)))
                .then(memoryService.remember(context, true))
                .then(remediationService.propose(context.diagnosisId(), alert, context.rootCause()))
                .map(action -> new DiagnosisEvent("remediation", action.status() + ": " + action.type()))
                .flux(),
            Mono.just(new DiagnosisEvent("done", "诊断完成"))
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
        record.setRelatedCaseIds(context.retrievedKnowledge().stream()
            .map(KnowledgeChunk::source)
            .toList());
        record.setCreatedAt(System.currentTimeMillis());
        record.setHumanVerified(false);
        return record;
    }

    private String sources(List<KnowledgeChunk> chunks) {
        return chunks.stream()
            .map(KnowledgeChunk::source)
            .distinct()
            .toList()
            .toString();
    }

    private String fallbackRootCause(DiagnosticContext context) {
        String text = context.toString().toLowerCase();
        if (text.contains("outofmemory") || text.contains("oom") || text.contains("heapusedpercent")) {
            return "LLM 不可用，规则降级: JVM 堆内存耗尽，证据为 OOM 日志、heapUsedPercent 高位和 GC pause 升高。";
        }
        return "LLM 不可用，规则降级: 需要结合日志、指标、链路继续排查。";
    }

    private String fallbackFix(DiagnosticContext context) {
        String text = context.toString().toLowerCase();
        if (text.contains("outofmemory") || text.contains("oom") || text.contains("heapusedpercent")) {
            return "先重启或扩容止血，抓取 heap dump 定位增长对象；代码修复必须走 PR + CI。";
        }
        return "先限流保护服务，保留现场数据，再按最小风险原则逐项恢复。";
    }
}
