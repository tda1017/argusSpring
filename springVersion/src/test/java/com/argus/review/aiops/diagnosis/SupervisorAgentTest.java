package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import com.argus.review.aiops.persistence.DiagnosisRecord;
import com.argus.review.aiops.persistence.DiagnosisRecordRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Supervisor 调度测试。
 */
class SupervisorAgentTest {

    @Test
    void shouldRunAgentsAndPersistDiagnosis() {
        List<DiagnosisRecord> saved = new ArrayList<>();
        DiagnosisRecordRepository repository = stubRepository(saved);
        SupervisorAgent supervisor = new SupervisorAgent(
            List.of(
                new StubAgent("log", Mono.just(new ToolResult("log", true, "logs", 3))),
                new StubAgent("metric", Mono.just(new ToolResult("metric", true, "metrics", 2))),
                new StubAgent("trace", Mono.just(new ToolResult("trace", true, "trace", 1)))
            ),
            new StubAnalyzer(),
            repository
        );

        StepVerifier.create(supervisor.diagnose(alert()).map(event -> event.type()))
            .expectNext("start", "tool_result", "tool_result", "tool_result", "root_cause", "fix", "done")
            .verifyComplete();

        assertEquals(1, saved.size());
        DiagnosisRecord record = saved.getFirst();
        assertEquals("root cause", record.getRootCause());
        assertEquals("fix suggestion", record.getFixSuggestion());
        assertEquals(3, record.getToolResults().size());
        assertEquals(List.of("log", "metric", "trace"),
            record.getToolResults().stream().map(ToolResult::agentName).toList());
    }

    @Test
    void shouldIsolateSingleAgentFailure() {
        List<DiagnosisRecord> saved = new ArrayList<>();
        DiagnosisRecordRepository repository = stubRepository(saved);
        SupervisorAgent supervisor = new SupervisorAgent(
            List.of(
                new StubAgent("log", Mono.just(new ToolResult("log", true, "logs", 1))),
                new StubAgent("metric", Mono.error(new IllegalStateException("metric down"))),
                new StubAgent("trace", Mono.just(new ToolResult("trace", true, "trace", 1)))
            ),
            new StubAnalyzer(),
            repository
        );

        StepVerifier.create(supervisor.diagnose(alert()).collectList())
            .assertNext(events -> {
                assertEquals("done", events.getLast().type());
                assertTrue(events.stream().anyMatch(event ->
                    event.type().equals("tool_result") && event.content().contains("metric down")));
            })
            .verifyComplete();

        List<ToolResult> results = saved.getFirst().getToolResults();
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(result -> result.agentName().equals("metric") && !result.success()));
    }

    @Test
    void shouldRunAgentsConcurrently() {
        List<DiagnosisRecord> saved = new ArrayList<>();
        DiagnosisRecordRepository repository = stubRepository(saved);
        AtomicInteger activeAgents = new AtomicInteger();
        AtomicInteger maxActiveAgents = new AtomicInteger();
        SupervisorAgent supervisor = new SupervisorAgent(
            List.of(
                new StubAgent("log", delayedResult("log", activeAgents, maxActiveAgents)),
                new StubAgent("metric", delayedResult("metric", activeAgents, maxActiveAgents)),
                new StubAgent("trace", delayedResult("trace", activeAgents, maxActiveAgents))
            ),
            new StubAnalyzer(),
            repository
        );

        StepVerifier.create(supervisor.diagnose(alert()).collectList())
            .expectNextCount(1)
            .verifyComplete();

        assertEquals(3, saved.getFirst().getToolResults().size());
        assertTrue(maxActiveAgents.get() > 1);
    }

    private static Mono<ToolResult> delayedResult(
        String name,
        AtomicInteger activeAgents,
        AtomicInteger maxActiveAgents
    ) {
        return Mono.defer(() -> {
            int active = activeAgents.incrementAndGet();
            maxActiveAgents.accumulateAndGet(active, Math::max);
            return Mono.delay(Duration.ofMillis(30))
                .map(ignored -> new ToolResult(name, true, name + " ok", 30))
                .doFinally(ignored -> activeAgents.decrementAndGet());
        });
    }

    @SuppressWarnings("unchecked")
    private static DiagnosisRecordRepository stubRepository(List<DiagnosisRecord> saved) {
        DiagnosisRecordRepository repository = mock(DiagnosisRecordRepository.class);
        when(repository.save(any(DiagnosisRecord.class))).thenAnswer(invocation -> {
            DiagnosisRecord record = invocation.getArgument(0);
            saved.add(record);
            return Mono.just(record);
        });
        return repository;
    }

    private static AlertEvent alert() {
        return new AlertEvent(
            "a1",
            "prometheus",
            "order-service",
            "JvmOom",
            "critical",
            "heap exhausted",
            Map.of("service", "order-service"),
            1L
        );
    }

    private record StubAgent(String name, Mono<ToolResult> result) implements DiagnosisAgent {
        @Override
        public Mono<ToolResult> execute(DiagnosticContext context) {
            return result;
        }
    }

    private static final class StubAnalyzer implements RootCauseAnalyzer {
        @Override
        public String analyze(DiagnosticContext context) {
            return "root cause";
        }

        @Override
        public String suggestFix(DiagnosticContext context) {
            return "fix suggestion";
        }
    }

}
