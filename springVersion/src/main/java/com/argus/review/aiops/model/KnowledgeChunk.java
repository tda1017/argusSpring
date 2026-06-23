package com.argus.review.aiops.model;

/**
 * RAG 检索命中的运维知识片段。
 */
public record KnowledgeChunk(
    String content,
    double score,
    String source
) {
}
