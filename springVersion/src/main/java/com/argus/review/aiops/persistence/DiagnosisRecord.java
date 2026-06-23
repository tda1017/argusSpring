package com.argus.review.aiops.persistence;

import com.argus.review.aiops.model.AlertEvent;
import com.argus.review.aiops.model.ToolResult;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * 完整诊断结果记录，供复盘和人工确认使用。
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "diagnosis_records")
public class DiagnosisRecord {

    @Id
    private String id;

    private String diagnosisId;
    private AlertEvent alert;
    private List<ToolResult> toolResults;
    private String rootCause;
    private String fixSuggestion;
    private List<String> relatedCaseIds;
    private long createdAt;
    private boolean humanVerified;
}
