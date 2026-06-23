package com.argus.review.aiops.ingestion;

import com.argus.review.aiops.convergence.ConvergenceService;
import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.persistence.AlertStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 告警接入接口，替代 MQ 做 MVP 输入面。
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertConverter alertConverter;
    private final ConvergenceService convergenceService;
    private final AlertStore alertStore;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AlertResponse> receive(@Valid @RequestBody Map<String, Object> payload) {
        AlertEvent alert = alertConverter.fromPrometheus(payload);
        return convergenceService.shouldSuppress(alert)
            .flatMap(suppressed -> alertStore.save(alert, suppressed)
                .map(saved -> new AlertResponse(saved.alertId(), suppressed)));
    }

    @GetMapping
    public Flux<AlertItem> list() {
        return alertStore.findRecent()
            .map(record -> new AlertItem(
                record.getAlertId(),
                record.getAlert().serviceName(),
                record.getAlert().alertName(),
                record.getAlert().severity(),
                record.getAlert().description(),
                record.isSuppressed(),
                record.getCreatedAt()
            ));
    }

    public record AlertResponse(String alertId, boolean suppressed) {
    }

    public record AlertItem(
        String alertId,
        String serviceName,
        String alertName,
        String severity,
        String description,
        boolean suppressed,
        long createdAt
    ) {
    }
}
