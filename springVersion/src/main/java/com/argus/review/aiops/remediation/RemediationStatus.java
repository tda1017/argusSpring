package com.argus.review.aiops.remediation;

/**
 * 自愈状态机状态。
 */
public enum RemediationStatus {
    PROPOSED,
    APPROVED,
    EXECUTING,
    VERIFIED,
    ROLLED_BACK,
    REJECTED
}
