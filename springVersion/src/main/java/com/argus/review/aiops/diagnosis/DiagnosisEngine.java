package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.DiagnosisEvent;
import reactor.core.publisher.Flux;

/**
 * 告警诊断引擎，输出结构化阶段事件。
 */
public interface DiagnosisEngine {

    Flux<DiagnosisEvent> diagnose(AlertEvent alert);
}
