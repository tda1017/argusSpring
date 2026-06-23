package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Mock 链路查询 Agent，返回固定慢调用链路样本。
 */
@Component
public class TraceQueryAgent extends DiagnosticAgentSupport {

    @Override
    public String name() {
        return "trace";
    }

    @Override
    public Mono<ToolResult> execute(DiagnosticContext context) {
        return measured(context, c -> querySlowTrace(c.alert().serviceName(), 15));
    }

    @Tool("查询指定服务最近慢调用链路")
    public String querySlowTrace(String serviceName, int minutes) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        int safeMinutes = Math.max(1, Math.min(minutes, 60));
        return "service=" + serviceName
            + ", window=" + safeMinutes + "m"
            + ", trace=HTTP POST /checkout -> OrderService.reserveStock -> InventoryDB.query, p99=2380ms";
    }
}
