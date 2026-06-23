package com.argus.review.aiops.remediation;

import org.springframework.stereotype.Component;

/**
 * 分级自治策略门。
 */
@Component
public class RemediationPolicy {

    private static final double AUTO_CONFIDENCE = 0.75;

    public boolean requiresApproval(RemediationAction action) {
        return action.type() == ActionType.CODE_FIX
            || action.blastRadius() == BlastRadius.HIGH
            || action.confidence() < AUTO_CONFIDENCE;
    }
}
