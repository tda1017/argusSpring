package com.argus.review.aiops.knowledge;

import com.argus.review.domain.rag.RetrievalService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AIOps 知识检索测试。
 */
class KnowledgeSearchServiceTest {

    @Test
    void shouldFallbackToJvmOomKnowledge() {
        KnowledgeSearchService service = new KnowledgeSearchService(new RetrievalService(null, null));

        var chunks = service.search("JVM OOM heap exhausted", 3);

        assertFalse(chunks.isEmpty());
        assertEquals("builtin:jvm-oom", chunks.getFirst().source());
    }
}
