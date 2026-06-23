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
            .thenReturn(Mono.empty());
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

    private static AlertEvent alert() {
        return new AlertEvent("a1", "prometheus", "order-service", "JvmOom", "critical", "oom", Map.of(), 1L);
    }
}
