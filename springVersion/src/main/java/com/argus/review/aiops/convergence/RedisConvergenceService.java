package com.argus.review.aiops.convergence;

import com.argus.review.aiops.model.AlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis SET NX EX 实现的分布式告警收敛。
 */
@Service
@RequiredArgsConstructor
public class RedisConvergenceService implements ConvergenceService {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${aiops.alert.convergence-window:PT5M}")
    private Duration convergenceWindow;

    @Override
    public Mono<Boolean> shouldSuppress(AlertEvent alert) {
        return redisTemplate.opsForValue()
            .setIfAbsent(key(alert), alert.alertId(), convergenceWindow)
            .map(set -> !set)
            .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> release(AlertEvent alert) {
        return redisTemplate.delete(key(alert)).then();
    }
}
