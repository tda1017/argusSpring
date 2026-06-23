package com.argus.review.aiops.memory;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosticContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Memory 闭环服务：记录服务画像和历史故障模式。
 */
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ServiceMemoryRepository repository;

    public Mono<String> profile(AlertEvent alert) {
        return repository.findFirstByServiceNameAndAlertName(alert.serviceName(), alert.alertName())
            .map(record -> "历史画像: service=%s, alert=%s, occurrences=%d, successes=%d, lastRootCause=%s"
                .formatted(record.getServiceName(), record.getAlertName(), record.getOccurrences(),
                    record.getVerifiedSuccesses(), record.getLastRootCause()))
            .defaultIfEmpty("历史画像: 暂无同服务同类告警记录");
    }

    public Mono<ServiceMemoryRecord> remember(DiagnosticContext context, boolean verified) {
        AlertEvent alert = context.alert();
        return repository.findFirstByServiceNameAndAlertName(alert.serviceName(), alert.alertName())
            .defaultIfEmpty(new ServiceMemoryRecord())
            .flatMap(record -> {
                record.setServiceName(alert.serviceName());
                record.setAlertName(alert.alertName());
                record.setLastRootCause(context.rootCause());
                record.setLastFixSuggestion(context.fixSuggestion());
                record.setOccurrences(record.getOccurrences() + 1);
                if (verified) {
                    record.setVerifiedSuccesses(record.getVerifiedSuccesses() + 1);
                }
                record.setUpdatedAt(System.currentTimeMillis());
                return repository.save(record);
            });
    }

    public Flux<ServiceMemoryRecord> recent() {
        return repository.findTop50ByOrderByUpdatedAtDesc();
    }
}
