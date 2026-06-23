package com.argus.review.aiops.remediation;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自愈熔断窗口测试。
 */
class CircuitBreakerServiceTest {

    @Test
    void shouldSetExpireOnlyWhenCounterCreated() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(Mono.just(2L));

        CircuitBreakerService service = new CircuitBreakerService(redis);

        StepVerifier.create(service.allow("order-service"))
            .expectNext(true)
            .verifyComplete();

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
