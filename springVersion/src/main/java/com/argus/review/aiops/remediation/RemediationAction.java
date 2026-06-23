package com.argus.review.aiops.remediation;

import com.argus.review.aiops.model.AlertEvent;

import java.util.UUID;

/**
 * 一次自愈动作。
 */
public record RemediationAction(
    String actionId,
    String diagnosisId,
    ActionType type,
    BlastRadius blastRadius,
    String targetService,
    String alertName,
    double confidence,
    RemediationStatus status,
    String reason,
    long createdAt,
    long updatedAt
) {
    public static RemediationAction propose(String diagnosisId, AlertEvent alert, ActionType type, double confidence, String reason) {
        BlastRadius radius = type == ActionType.CODE_FIX ? BlastRadius.HIGH : BlastRadius.LOW;
        long now = System.currentTimeMillis();
        return new RemediationAction(
            UUID.randomUUID().toString(),
            diagnosisId,
            type,
            radius,
            alert.serviceName(),
            alert.alertName(),
            confidence,
            RemediationStatus.PROPOSED,
            reason,
            now,
            now
        );
    }

    public RemediationAction withStatus(RemediationStatus next) {
        return new RemediationAction(
            actionId,
            diagnosisId,
            type,
            blastRadius,
            targetService,
            alertName,
            confidence,
            next,
            reason,
            createdAt,
            System.currentTimeMillis()
        );
    }
}
