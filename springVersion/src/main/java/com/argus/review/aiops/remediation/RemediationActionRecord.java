package com.argus.review.aiops.remediation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 自愈动作当前状态。
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "remediation_actions")
public class RemediationActionRecord {

    @Id
    private String id;

    @Indexed(unique = true)
    private String actionId;

    private String diagnosisId;
    private ActionType type;
    private BlastRadius blastRadius;
    private String targetService;
    private String alertName;
    private double confidence;
    private RemediationStatus status;
    private String reason;
    private long createdAt;
    private long updatedAt;

    public RemediationActionRecord(RemediationAction action) {
        this.actionId = action.actionId();
        this.diagnosisId = action.diagnosisId();
        this.type = action.type();
        this.blastRadius = action.blastRadius();
        this.targetService = action.targetService();
        this.alertName = action.alertName();
        this.confidence = action.confidence();
        this.status = action.status();
        this.reason = action.reason();
        this.createdAt = action.createdAt();
        this.updatedAt = action.updatedAt();
    }

    public RemediationAction toAction() {
        return new RemediationAction(
            actionId,
            diagnosisId,
            type,
            blastRadius,
            targetService,
            alertName,
            confidence,
            status,
            reason,
            createdAt,
            updatedAt
        );
    }
}
