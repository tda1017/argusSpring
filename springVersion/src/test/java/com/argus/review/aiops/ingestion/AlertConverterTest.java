package com.argus.review.aiops.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prometheus 告警标准化测试。
 */
class AlertConverterTest {

    private final AlertConverter converter = new AlertConverter();

    @Test
    void shouldConvertPrometheusWebhook() {
        var event = converter.fromPrometheus(Map.of(
            "alerts", List.of(Map.of(
                "labels", Map.of(
                    "alertname", "JvmOutOfMemory",
                    "service", "order-service",
                    "severity", "critical"
                ),
                "annotations", Map.of("description", "heap usage too high")
            ))
        ));

        assertFalse(event.alertId().isBlank());
        assertEquals("prometheus", event.source());
        assertEquals("order-service", event.serviceName());
        assertEquals("JvmOutOfMemory", event.alertName());
        assertEquals("critical", event.severity());
        assertEquals("heap usage too high", event.description());
    }
}
