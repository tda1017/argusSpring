package com.argus.review.domain.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索服务。
 * <p>在代码审查前，根据代码 Diff 提取关键词并检索最匹配的内部规范片段。</p>
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /**
     * 检索与查询最相关的内部规范片段。
     *
     * @param query  查询文本（如代码 Diff 或提取的关键词）
     * @param maxResults 最大返回片段数
     * @param minScore  最小相似度阈值 (0.0 ~ 1.0)
     * @return 拼接后的规范上下文文本
     */
    public String retrieveRelevantContext(String query, int maxResults, double minScore) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(
            queryEmbedding, maxResults, minScore
        );

        if (matches.isEmpty()) {
            return "暂无相关内部规范。";
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            contextBuilder.append("--- 规范片段 ").append(i + 1)
                .append(" (相似度: ").append(String.format("%.4f", match.score())).append(") ---\n")
                .append(match.embedded().text())
                .append("\n\n");
        }

        return contextBuilder.toString().trim();
    }

    /**
     * 使用默认参数进行检索（返回 3 条，阈值 0.7）。
     */
    public String retrieveRelevantContext(String query) {
        return retrieveRelevantContext(query, 3, 0.7);
    }

}
