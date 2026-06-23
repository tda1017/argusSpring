package com.argus.review.aiops.convergence;

import com.argus.review.aiops.model.AlertEvent;
import reactor.core.publisher.Mono;

/**
 * 告警收敛服务，返回 true 表示重复告警被抑制。
 */
public interface ConvergenceService {

    Mono<Boolean> shouldSuppress(AlertEvent alert);

    Mono<Void> release(AlertEvent alert);

    default String key(AlertEvent alert) {
        return "alert:converge:" + alert.serviceName() + ":" + alert.alertName();
    }
}
