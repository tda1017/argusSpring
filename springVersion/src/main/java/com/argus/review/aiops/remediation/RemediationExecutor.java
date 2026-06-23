package com.argus.review.aiops.remediation;

import reactor.core.publisher.Mono;

/**
 * 自愈动作执行器。
 */
public interface RemediationExecutor {

    Mono<String> execute(RemediationAction action);
}
