package com.argus.review.aiops.remediation;

import com.argus.review.aiops.memory.MemoryService;
import com.argus.review.aiops.memory.ServiceMemoryRecord;
import com.argus.review.aiops.model.AlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 自愈状态机服务。
 */
@Service
@RequiredArgsConstructor
public class RemediationService {

    private final RemediationPolicy policy;
    private final CircuitBreakerService circuitBreaker;
    private final RemediationExecutor executor;
    private final RemediationAuditRepository auditRepository;
    private final RemediationActionRepository actionRepository;
    private final MemoryService memoryService;

    public Mono<RemediationAction> propose(String diagnosisId, AlertEvent alert, String rootCause) {
        RemediationAction proposed = RemediationAction.propose(
            diagnosisId,
            alert,
            chooseAction(alert, rootCause),
            confidence(alert, rootCause),
            rootCause == null || rootCause.isBlank() ? alert.description() : rootCause
        );

        return memoryService.findPattern(alert)
            .defaultIfEmpty(new ServiceMemoryRecord())
            .flatMap(memory -> {
                boolean promoted = memoryService.shouldPromote(memory);
                if (policy.requiresApproval(proposed, memory, promoted)) {
                    return saveAction(proposed).then(audit(proposed, "等待人工审批"));
                }
                String approvalMessage = promoted ? "memory 提权自动批准" : "策略门自动批准";
                return circuitBreaker.allow(alert.serviceName())
                    .flatMap(allowed -> allowed
                        ? execute(proposed, approvalMessage)
                        : saveAction(proposed.withStatus(RemediationStatus.REJECTED))
                            .then(audit(proposed.withStatus(RemediationStatus.REJECTED), "熔断器触发")));
            });
    }

    public Flux<RemediationAuditRecord> recentAudits() {
        return auditRepository.findTop50ByOrderByCreatedAtDesc();
    }

    public Flux<RemediationActionRecord> recentActions() {
        return actionRepository.findTop50ByOrderByUpdatedAtDesc();
    }

    public Mono<RemediationAction> approve(String actionId) {
        return actionRepository.findByActionId(actionId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("动作不存在: " + actionId)))
            .map(RemediationActionRecord::toAction)
            .flatMap(action -> {
                if (action.status() != RemediationStatus.PROPOSED) {
                    return Mono.error(new IllegalStateException("动作状态不可审批: " + action.status()));
                }
                if (action.type() == ActionType.CODE_FIX) {
                    RemediationAction approved = action.withStatus(RemediationStatus.APPROVED);
                    return saveAction(approved).then(audit(approved, "CODE_FIX 已审批，等待 PR + CI"));
                }
                return execute(action, "人工批准");
            });
    }

    public Mono<RemediationAction> reject(String actionId) {
        return actionRepository.findByActionId(actionId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("动作不存在: " + actionId)))
            .map(RemediationActionRecord::toAction)
            .map(action -> action.withStatus(RemediationStatus.REJECTED))
            .flatMap(action -> saveAction(action).then(audit(action, "人工拒绝")));
    }

    private Mono<RemediationAction> execute(RemediationAction action, String approvalMessage) {
        RemediationAction approved = action.withStatus(RemediationStatus.APPROVED);
        return saveAction(approved)
            .then(auditRepository.save(new RemediationAuditRecord(approved, approvalMessage)))
            .thenReturn(approved.withStatus(RemediationStatus.EXECUTING))
            .flatMap(executing -> executor.execute(executing)
                .flatMap(message -> {
                    RemediationAction verified = executing.withStatus(RemediationStatus.VERIFIED);
                    return saveAction(verified)
                        .then(memoryService.recordVerifiedSuccess(verified))
                        .then(audit(verified, message));
                })
                .onErrorResume(e -> {
                    RemediationAction rolledBack = executing.withStatus(RemediationStatus.ROLLED_BACK);
                    return saveAction(rolledBack).then(audit(rolledBack, e.getMessage()));
                }));
    }

    private Mono<RemediationAction> audit(RemediationAction action, String message) {
        return auditRepository.save(new RemediationAuditRecord(action, message))
            .thenReturn(action);
    }

    private Mono<RemediationActionRecord> saveAction(RemediationAction action) {
        return actionRepository.findByActionId(action.actionId())
            .defaultIfEmpty(new RemediationActionRecord(action))
            .flatMap(record -> {
                RemediationActionRecord updated = new RemediationActionRecord(action);
                updated.setId(record.getId());
                return actionRepository.save(updated);
            });
    }

    private ActionType chooseAction(AlertEvent alert, String rootCause) {
        String text = ((alert.alertName() == null ? "" : alert.alertName()) + " " + (rootCause == null ? "" : rootCause)).toLowerCase();
        if (text.contains("code") || text.contains("代码")) {
            return ActionType.CODE_FIX;
        }
        if (text.contains("oom") || text.contains("memory") || text.contains("heap")) {
            return ActionType.RESTART;
        }
        if (text.contains("redis")) {
            return ActionType.CLEAR_CACHE;
        }
        return ActionType.RATE_LIMIT;
    }

    private double confidence(AlertEvent alert, String rootCause) {
        String text = ((alert.alertName() == null ? "" : alert.alertName()) + " " + (rootCause == null ? "" : rootCause)).toLowerCase();
        return text.contains("oom") || text.contains("redis") ? 0.82 : 0.68;
    }
}
