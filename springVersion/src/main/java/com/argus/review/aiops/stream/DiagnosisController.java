package com.argus.review.aiops.stream;

import com.argus.review.aiops.diagnosis.DiagnosisEngine;
import com.argus.review.aiops.model.DiagnosisEvent;
import com.argus.review.aiops.persistence.AlertStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AIOps 诊断 SSE 输出接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {

    private final AlertStore alertStore;
    private final DiagnosisEngine diagnosisEngine;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String alertId) {
        return alertStore.findByAlertId(alertId)
            .flatMapMany(diagnosisEngine::diagnose)
            .switchIfEmpty(Flux.just(new DiagnosisEvent("error", "告警不存在: " + alertId)))
            .map(event -> ServerSentEvent.builder(event.content())
                .event(event.type())
                .build())
            .onErrorResume(e -> {
                log.error("诊断 SSE 输出异常: alertId={}", alertId, e);
                return Flux.just(ServerSentEvent.builder(e.getMessage()).event("error").build());
            });
    }
}
