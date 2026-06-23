package com.argus.review.aiops.model;

import java.util.Map;

/**
 * 标准化后的告警事件。
 */
public record AlertEvent(
    String alertId,
    String source,
    String serviceName,
    String alertName,
    String severity,
    String description,
    Map<String, String> labels,
    long firedAt
) {
}
