package com.argus.review.aiops.persistence;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

/**
 * 告警记录仓库。
 */
public interface AlertRecordRepository extends ReactiveMongoRepository<AlertRecord, String> {

    Mono<AlertRecord> findByAlertId(String alertId);
}
