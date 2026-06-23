package com.argus.review.aiops.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次诊断的可变上下文，只由 Supervisor 串行汇总写入。
 */
public class DiagnosticContext {

    private final String diagnosisId;
    private final AlertEvent alert;
    private final List<ToolResult> toolResults = new ArrayList<>();
    private final List<KnowledgeChunk> retrievedKnowledge = new ArrayList<>();
    private String rootCause;
    private String fixSuggestion;

    public DiagnosticContext(AlertEvent alert) {
        this.alert = alert;
        this.diagnosisId = UUID.randomUUID().toString();
    }

    public String diagnosisId() {
        return diagnosisId;
    }

    public AlertEvent alert() {
        return alert;
    }

    public List<ToolResult> toolResults() {
        return List.copyOf(toolResults);
    }

    public List<KnowledgeChunk> retrievedKnowledge() {
        return List.copyOf(retrievedKnowledge);
    }

    public String rootCause() {
        return rootCause;
    }

    public String fixSuggestion() {
        return fixSuggestion;
    }

    public void addAllToolResults(List<ToolResult> results) {
        toolResults.addAll(results);
    }

    public void addAllKnowledge(List<KnowledgeChunk> chunks) {
        retrievedKnowledge.addAll(chunks);
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public void setFixSuggestion(String fixSuggestion) {
        this.fixSuggestion = fixSuggestion;
    }

    @Override
    public String toString() {
        return """
            diagnosisId=%s
            alert=%s/%s/%s
            description=%s
            labels=%s
            toolResults=%s
            retrievedKnowledge=%s
            rootCause=%s
            fixSuggestion=%s
            """.formatted(
            diagnosisId,
            alert.serviceName(),
            alert.alertName(),
            alert.severity(),
            alert.description(),
            alert.labels(),
            toolResults,
            retrievedKnowledge,
            rootCause,
            fixSuggestion
        );
    }
}
