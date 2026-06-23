package com.argus.review.aiops.memory;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosticContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Memory 闭环测试。
 */
class MemoryServiceTest {

    @Test
    void shouldRememberServiceFaultPattern() {
        AtomicReference<ServiceMemoryRecord> stored = new AtomicReference<>();
        ServiceMemoryRepository repository = mock(ServiceMemoryRepository.class);
        when(repository.findFirstByServiceNameAndAlertName("order-service", "JvmOom"))
            .thenAnswer(invocation -> stored.get() == null ? Mono.empty() : Mono.just(stored.get()));
        when(repository.save(org.mockito.ArgumentMatchers.any(ServiceMemoryRecord.class))).thenAnswer(invocation -> {
            ServiceMemoryRecord record = invocation.getArgument(0);
            stored.set(record);
            return Mono.just(record);
        });

        MemoryService service = new MemoryService(repository);
        DiagnosticContext context = new DiagnosticContext(alert());
        context.setRootCause("heap leak");
        context.setFixSuggestion("restart and dump heap");

        StepVerifier.create(service.remember(context, true))
            .expectNextMatches(record -> record.getOccurrences() == 1 && record.getVerifiedSuccesses() == 1)
            .verifyComplete();
    }

    @Test
    void shouldRecordOccurrenceWithoutVerifiedSuccess() {
        ServiceMemoryRepository repository = repository(Mono.empty());
        MemoryService service = new MemoryService(repository);
        DiagnosticContext context = new DiagnosticContext(alert());

        StepVerifier.create(service.recordOccurrence(context))
            .expectNextMatches(record -> record.getOccurrences() == 1 && record.getVerifiedSuccesses() == 0)
            .verifyComplete();
    }

    @Test
    void shouldPromoteOnlyHighSuccessPatterns() {
        MemoryService service = new MemoryService(mock(ServiceMemoryRepository.class));
        ServiceMemoryRecord record = new ServiceMemoryRecord();
        record.setOccurrences(4);
        record.setVerifiedSuccesses(3);

        org.junit.jupiter.api.Assertions.assertTrue(service.shouldPromote(record));
    }

    private static ServiceMemoryRepository repository(Mono<ServiceMemoryRecord> existing) {
        ServiceMemoryRepository repository = mock(ServiceMemoryRepository.class);
        when(repository.findFirstByServiceNameAndAlertName("order-service", "JvmOom"))
            .thenReturn(existing);
        when(repository.save(org.mockito.ArgumentMatchers.any(ServiceMemoryRecord.class))).thenAnswer(invocation ->
            Mono.just(invocation.getArgument(0))
        );
        return repository;
    }

    private static AlertEvent alert() {
        return new AlertEvent("a1", "prometheus", "order-service", "JvmOom", "critical", "oom", Map.of(), 1L);
    }
}
