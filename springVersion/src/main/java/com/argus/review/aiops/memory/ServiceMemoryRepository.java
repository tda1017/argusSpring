package com.argus.review.aiops.memory;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 服务记忆仓库。
 */
public interface ServiceMemoryRepository extends ReactiveMongoRepository<ServiceMemoryRecord, String> {

    Mono<ServiceMemoryRecord> findFirstByServiceNameAndAlertName(String serviceName, String alertName);

    Flux<ServiceMemoryRecord> findTop50ByOrderByUpdatedAtDesc();
}
