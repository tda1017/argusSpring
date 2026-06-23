package com.argus.review.aiops.remediation;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Mock 执行器。真实执行端后续替换，接口不变。
 */
@Component
public class MockRemediationExecutor implements RemediationExecutor {

    @Override
    public Mono<String> execute(RemediationAction action) {
        String message = switch (action.type()) {
            case RESTART -> "已模拟重启 " + action.targetService();
            case SCALE -> "已模拟扩容 " + action.targetService();
            case ROLLBACK -> "已模拟回滚 " + action.targetService();
            case RATE_LIMIT -> "已模拟限流 " + action.targetService();
            case CLEAR_CACHE -> "已模拟清理缓存 " + action.targetService();
            case CODE_FIX -> "CODE_FIX 只生成 PR/待审批，不自动执行";
        };
        return Mono.just(message);
    }
}
