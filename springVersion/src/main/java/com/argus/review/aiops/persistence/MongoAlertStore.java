package com.argus.review.aiops.persistence;

import com.argus.review.aiops.model.AlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * MongoDB 告警存储实现。
 */
@Service
@RequiredArgsConstructor
public class MongoAlertStore implements AlertStore {

    private final AlertRecordRepository repository;

    @Override
    public Mono<AlertEvent> save(AlertEvent alert, boolean suppressed) {
        return repository.save(new AlertRecord(alert, suppressed))
            .map(AlertRecord::getAlert);
    }

    @Override
    public Mono<AlertEvent> findByAlertId(String alertId) {
        return repository.findByAlertId(alertId)
            .map(AlertRecord::getAlert);
    }
}
