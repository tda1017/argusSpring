package com.argus.review.aiops.knowledge;

import org.springframework.stereotype.Service;

/**
 * AIOps 文档注入门面。
 * 现阶段复用全局 DocumentIngestionService 的启动预加载，避免同一目录重复写入向量库。
 */
@Service
public class DocumentIngestor {

    public void ingestOnStartup() {
        // 启动预加载由 DocumentIngestionService 统一负责。
    }
}
