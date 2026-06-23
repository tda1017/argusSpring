package com.argus.review.aiops.remediation;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

/**
 * 自愈审计仓库。
 */
public interface RemediationAuditRepository extends ReactiveMongoRepository<RemediationAuditRecord, String> {

    Flux<RemediationAuditRecord> findTop50ByOrderByCreatedAtDesc();
}
