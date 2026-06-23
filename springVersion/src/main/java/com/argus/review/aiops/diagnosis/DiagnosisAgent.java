package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 单个诊断工具 Agent。
 */
public interface DiagnosisAgent {

    String name();

    Mono<ToolResult> execute(DiagnosticContext context);
}
