package com.argus.review.aiops.remediation;

import com.argus.review.aiops.memory.ServiceMemoryRecord;
import org.springframework.stereotype.Component;

/**
 * 分级自治策略门。
 */
@Component
public class RemediationPolicy {

    private static final double AUTO_CONFIDENCE = 0.75;

    public boolean requiresApproval(RemediationAction action) {
        return requiresApproval(action, null, false);
    }

    public boolean requiresApproval(RemediationAction action, ServiceMemoryRecord memory, boolean memoryPromoted) {
        if (memoryPromoted && memory != null && action.type() != ActionType.CODE_FIX && action.blastRadius() != BlastRadius.HIGH) {
            return false;
        }
        return action.type() == ActionType.CODE_FIX
            || action.blastRadius() == BlastRadius.HIGH
            || action.confidence() < AUTO_CONFIDENCE;
    }
}
