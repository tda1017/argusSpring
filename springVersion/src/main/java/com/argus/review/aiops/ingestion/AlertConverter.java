package com.argus.review.aiops.ingestion;

import com.argus.review.aiops.model.AlertEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 外部告警格式标准化。MVP 先支持 Prometheus Alertmanager webhook。
 */
@Component
public class AlertConverter {

    @SuppressWarnings("unchecked")
    public AlertEvent fromPrometheus(Map<String, Object> payload) {
        Map<String, Object> alert = firstAlert(payload);
        Map<String, String> labels = stringMap((Map<String, Object>) alert.get("labels"));
        Map<String, String> annotations = stringMap((Map<String, Object>) alert.get("annotations"));

        String alertName = firstNonBlank(labels.get("alertname"), "UnknownAlert");
        String serviceName = firstNonBlank(labels.get("service"), labels.get("job"), labels.get("instance"), "unknown-service");
        String severity = firstNonBlank(labels.get("severity"), "warning");
        String description = firstNonBlank(
            annotations.get("description"),
            annotations.get("summary"),
            alertName + " on " + serviceName
        );

        return new AlertEvent(
            UUID.randomUUID().toString(),
            "prometheus",
            serviceName,
            alertName,
            severity,
            description,
            labels,
            System.currentTimeMillis()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstAlert(Map<String, Object> payload) {
        Object alerts = payload.get("alerts");
        if (alerts instanceof java.util.List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return (Map<String, Object>) first;
        }
        return payload;
    }

    private Map<String, String> stringMap(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
