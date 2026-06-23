package com.argus.review.aiops.remediation;

/**
 * 自愈动作白名单。
 */
public enum ActionType {
    RESTART,
    SCALE,
    ROLLBACK,
    RATE_LIMIT,
    CLEAR_CACHE,
    CODE_FIX
}
