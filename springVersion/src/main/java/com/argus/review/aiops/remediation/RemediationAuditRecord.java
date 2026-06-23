package com.argus.review.aiops.remediation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 自愈审计记录。
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "remediation_audits")
public class RemediationAuditRecord {

    @Id
    private String id;

    private String actionId;
    private String diagnosisId;
    private ActionType type;
    private BlastRadius blastRadius;
    private String targetService;
    private double confidence;
    private RemediationStatus status;
    private String reason;
    private String message;
    private long createdAt;

    public RemediationAuditRecord(RemediationAction action, String message) {
        this.actionId = action.actionId();
        this.diagnosisId = action.diagnosisId();
        this.type = action.type();
        this.blastRadius = action.blastRadius();
        this.targetService = action.targetService();
        this.confidence = action.confidence();
        this.status = action.status();
        this.reason = action.reason();
        this.message = message;
        this.createdAt = System.currentTimeMillis();
    }
}
