package com.argus.review.aiops.persistence;

import com.argus.review.aiops.model.AlertEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 告警接入记录，供 SSE 阶段按 alertId 找回原始告警。
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "alerts")
public class AlertRecord {

    @Id
    private String id;

    @Indexed(unique = true)
    private String alertId;

    private AlertEvent alert;
    private long createdAt;
    private boolean suppressed;

    public AlertRecord(AlertEvent alert, boolean suppressed) {
        this.alertId = alert.alertId();
        this.alert = alert;
        this.createdAt = System.currentTimeMillis();
        this.suppressed = suppressed;
    }
}
