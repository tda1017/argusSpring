package com.argus.review.aiops.remediation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 自愈熔断器：同服务短时间动作过多则停手。
 */
@Service
@RequiredArgsConstructor
public class CircuitBreakerService {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<Boolean> allow(String serviceName) {
        String key = "aiops:remediation:circuit:" + serviceName;
        return redisTemplate.opsForValue().increment(key)
            .flatMap(count -> {
                if (count == 1L) {
                    return redisTemplate.expire(key, WINDOW).thenReturn(true);
                }
                return Mono.just(count <= LIMIT);
            })
            .onErrorReturn(true);
    }
}
