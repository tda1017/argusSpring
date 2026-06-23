package com.argus.review.aiops.convergence;

import com.argus.review.aiops.model.AlertEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 收敛 key 规则测试。
 */
class RedisConvergenceServiceTest {

    @Test
    void shouldBuildStableConvergenceKey() {
        ConvergenceService service = new ConvergenceService() {
            @Override
            public reactor.core.publisher.Mono<Boolean> shouldSuppress(AlertEvent alert) {
                return reactor.core.publisher.Mono.just(false);
            }

            @Override
            public reactor.core.publisher.Mono<Void> release(AlertEvent alert) {
                return reactor.core.publisher.Mono.empty();
            }
        };

        AlertEvent alert = new AlertEvent(
            "a1", "prometheus", "order-service", "JvmOom", "critical", "oom", Map.of(), 1L
        );

        assertEquals("alert:converge:order-service:JvmOom", service.key(alert));
    }
}
