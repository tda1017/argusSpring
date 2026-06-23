package com.argus.review.aiops.knowledge;

import com.argus.review.aiops.model.KnowledgeChunk;
import com.argus.review.domain.rag.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AIOps 知识检索服务，包装旧 RAG 能力并返回结构化 chunk。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {

    private final RetrievalService retrievalService;

    public List<KnowledgeChunk> search(String query, int maxResults) {
        try {
            List<KnowledgeChunk> chunks = retrievalService.search(query, maxResults, 0.45).stream()
                .map(match -> new KnowledgeChunk(match.embedded().text(), match.score(), sourceOf(match.embedded().text())))
                .toList();
            if (!chunks.isEmpty()) {
                return chunks;
            }
        } catch (Exception ignored) {
            // RAG 失败不能拖垮诊断链路，下面返回内置兜底知识。
        }
        return fallback(query).stream().limit(maxResults).toList();
    }

    private List<KnowledgeChunk> fallback(String query) {
        String normalized = query == null ? "" : query.toLowerCase();
        if (normalized.contains("oom") || normalized.contains("memory") || normalized.contains("jvm")) {
            return List.of(new KnowledgeChunk(
                "故障现象: JVM OOM 或堆使用率持续高位。排查步骤: 看 ERROR 日志、GC pause、heapUsedPercent、最近发布。根因: 内存泄漏、批量加载过大或缓存无上限。修复方案: 先扩容/重启止血,再抓 heap dump 定位对象增长。",
                0.8,
                "builtin:jvm-oom"
            ));
        }
        return List.of(new KnowledgeChunk(
            "通用排查: 先确认告警是否重复,再按日志、指标、链路、最近变更四条线交叉验证,最后选择最小风险处置动作。",
            0.5,
            "builtin:generic"
        ));
    }

    private String sourceOf(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.lines()
            .filter(line -> line.startsWith("来源文件:"))
            .map(line -> line.substring("来源文件:".length()).trim())
            .findFirst()
            .orElse("rag");
    }
}
