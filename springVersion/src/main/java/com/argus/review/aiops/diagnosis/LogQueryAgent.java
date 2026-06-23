package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Mock 日志查询 Agent，后续替换为 Loki / Elasticsearch 适配器。
 */
@Component
public class LogQueryAgent extends DiagnosticAgentSupport {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public Mono<ToolResult> execute(DiagnosticContext context) {
        return measured(context, c -> queryRecentLogs(c.alert().serviceName(), 15));
    }

    @Tool("查询指定服务最近一段时间的错误日志")
    public String queryRecentLogs(String serviceName, int minutes) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        int safeMinutes = Math.max(1, Math.min(minutes, 60));
        return "service=" + serviceName
            + ", window=" + safeMinutes + "m"
            + ", level=ERROR, message=java.lang.OutOfMemoryError: Java heap space";
    }
}
