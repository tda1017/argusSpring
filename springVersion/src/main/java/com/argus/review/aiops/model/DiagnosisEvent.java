package com.argus.review.aiops.model;

/**
 * SSE 推送给前端的诊断阶段事件。
 */
public record DiagnosisEvent(
    String type,
    String content
) {
}
