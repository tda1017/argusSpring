package com.argus.review.aiops.persistence;

import com.argus.review.aiops.model.AlertEvent;
import reactor.core.publisher.Mono;

/**
 * 告警存取接口，隔离控制器和 Mongo 细节。
 */
public interface AlertStore {

    Mono<AlertEvent> save(AlertEvent alert, boolean suppressed);

    Mono<AlertEvent> findByAlertId(String alertId);
}
