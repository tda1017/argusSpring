package com.argus.review.aiops.ingestion;

import com.argus.review.aiops.convergence.ConvergenceService;
import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.persistence.AlertStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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

    public record AlertResponse(String alertId, boolean suppressed) {
    }
}
