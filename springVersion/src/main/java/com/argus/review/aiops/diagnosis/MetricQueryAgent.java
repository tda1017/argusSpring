package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import com.argus.review.aiops.model.ToolResult;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Mock 指标查询 Agent。查询条件只走白名单分支，不拼外部查询串。
 */
@Component
public class MetricQueryAgent extends DiagnosticAgentSupport {

    @Override
    public String name() {
        return "metric";
    }

    @Override
    public Mono<ToolResult> execute(DiagnosticContext context) {
        return measured(context, c -> queryMetrics(c.alert().serviceName(), c.alert().alertName()));
    }

    @Tool("查询指定服务的关键指标快照")
    public String queryMetrics(String serviceName, String alertName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        String metricSet = switch (normalize(alertName)) {
            case "jvmoom", "oom", "highmemoryusage" ->
                "\"heapUsedPercent\": 96.8, \"gcPauseP99Ms\": 1840";
            case "highcpuusage", "cpu" ->
                "\"cpuUsagePercent\": 91.2, \"loadAverage\": 8.7";
            default ->
                "\"cpuUsagePercent\": 47.5, \"heapUsedPercent\": 63.1";
        };
        return "{\"service\":\"" + serviceName + "\", " + metricSet + "}";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }
}
