package com.argus.review.aiops.model;

/**
 * 单个诊断工具 Agent 的执行结果。
 */
public record ToolResult(
    String agentName,
    boolean success,
    String output,
    long latencyMs
) {
}
