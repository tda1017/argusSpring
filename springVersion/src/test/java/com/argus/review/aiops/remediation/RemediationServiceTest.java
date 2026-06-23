package com.argus.review.aiops.remediation;

import com.argus.review.aiops.model.AlertEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自愈状态机测试。
 */
class RemediationServiceTest {

    @Test
    void shouldAutoExecuteLowRiskAction() {
        List<RemediationAuditRecord> audits = new ArrayList<>();
        RemediationService service = new RemediationService(
            new RemediationPolicy(),
            allowBreaker(),
            action -> Mono.just("done"),
            auditRepository(audits),
            actionRepository(new ArrayList<>())
        );

        StepVerifier.create(service.propose("d1", alert("JvmOom"), "heap oom"))
            .expectNextMatches(action -> action.status() == RemediationStatus.VERIFIED)
            .verifyComplete();
    }

    @Test
    void shouldKeepCodeFixWaitingForApproval() {
        RemediationService service = new RemediationService(
            new RemediationPolicy(),
            allowBreaker(),
            action -> Mono.just("done"),
            auditRepository(new ArrayList<>()),
            actionRepository(new ArrayList<>())
        );

        StepVerifier.create(service.propose("d1", alert("CodeRegression"), "code bug"))
            .expectNextMatches(action -> action.status() == RemediationStatus.PROPOSED && action.type() == ActionType.CODE_FIX)
            .verifyComplete();
    }

    private static CircuitBreakerService allowBreaker() {
        CircuitBreakerService breaker = mock(CircuitBreakerService.class);
        when(breaker.allow("order-service")).thenReturn(Mono.just(true));
        return breaker;
    }

    private static RemediationAuditRepository auditRepository(List<RemediationAuditRecord> audits) {
        RemediationAuditRepository repository = mock(RemediationAuditRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any(RemediationAuditRecord.class))).thenAnswer(invocation -> {
            RemediationAuditRecord record = invocation.getArgument(0);
            audits.add(record);
            return Mono.just(record);
        });
        when(repository.findTop50ByOrderByCreatedAtDesc()).thenReturn(Flux.fromIterable(audits));
        return repository;
    }

    private static RemediationActionRepository actionRepository(List<RemediationActionRecord> actions) {
        RemediationActionRepository repository = mock(RemediationActionRepository.class);
        when(repository.findByActionId(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(RemediationActionRecord.class))).thenAnswer(invocation -> {
            RemediationActionRecord record = invocation.getArgument(0);
            actions.add(record);
            return Mono.just(record);
        });
        when(repository.findTop50ByOrderByUpdatedAtDesc()).thenReturn(Flux.fromIterable(actions));
        return repository;
    }

    private static AlertEvent alert(String alertName) {
        return new AlertEvent("a1", "prometheus", "order-service", alertName, "critical", alertName, Map.of(), 1L);
    }
}
