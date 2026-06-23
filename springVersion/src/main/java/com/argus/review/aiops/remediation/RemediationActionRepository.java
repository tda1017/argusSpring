package com.argus.review.aiops.remediation;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 自愈动作仓库。
 */
public interface RemediationActionRepository extends ReactiveMongoRepository<RemediationActionRecord, String> {

    Mono<RemediationActionRecord> findByActionId(String actionId);

    Flux<RemediationActionRecord> findTop50ByOrderByUpdatedAtDesc();
}
