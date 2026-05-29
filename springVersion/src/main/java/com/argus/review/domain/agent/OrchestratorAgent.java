package com.argus.review.domain.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Agent 编排器。
 * <p>只做确定性路由，不调用 LLM，避免把简单控制流做成不可测黑箱。</p>
 */
@Component
public class OrchestratorAgent {

    /**
     * 根据安全审查结果决定是否把问题路由给 FixAgent。
     *
     * @param securityReport 安全审查报告
     * @return 需要修复时返回一条 Security -> Fix 消息
     */
    public Optional<AgentMessage> routeSecurityFindings(String securityReport) {
        if (!hasActionableFinding(securityReport)) {
            return Optional.empty();
        }
        return Optional.of(new AgentMessage(
            AgentRole.SECURITY,
            AgentRole.FIX,
            "GENERATE_SECURITY_FIX",
            securityReport
        ));
    }

    /**
     * 宽松判断是否存在可修复问题。
     * LLM 输出不是结构化协议，只能先用保守关键词兜底。
     */
    private boolean hasActionableFinding(String report) {
        if (report == null || report.isBlank()) {
            return false;
        }
        String normalized = report.toLowerCase(Locale.ROOT);
        return !(normalized.contains("无安全问题")
            || normalized.contains("未发现安全问题")
            || normalized.contains("no security issue")
            || normalized.contains("no vulnerabilities"));
    }
}
