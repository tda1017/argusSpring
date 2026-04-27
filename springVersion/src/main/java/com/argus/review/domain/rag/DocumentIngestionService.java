package com.argus.review.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 规范文档预加载服务。
 * <p>启动时将指定目录下的规范文档切分、向量化并写入 EmbeddingStore。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${argus.rag.preload.enabled:true}")
    private boolean preloadEnabled;

    @Value("${argus.rag.preload.directory:docs/specs}")
    private String preloadDirectory;

    @Value("${argus.rag.preload.clear-before-load:false}")
    private boolean clearBeforeLoad;

    @Value("${argus.rag.preload.extensions:md,txt,java}")
    private String preloadExtensions;

    /**
     * 应用启动后预加载规范文档到向量库。
     */
    @PostConstruct
    public void ingestOnStartup() {
        if (!preloadEnabled) {
            log.info("[RAG] 文档预加载已禁用");
            return;
        }

        Path directory = Path.of(preloadDirectory);
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            log.warn("[RAG] 预加载目录不存在，跳过: {}", directory.toAbsolutePath());
            return;
        }

        List<Document> documents;
        try {
            documents = loadDocuments(directory, parseExtensions(preloadExtensions));
        } catch (IOException e) {
            throw new IllegalStateException("加载 RAG 文档失败: " + directory.toAbsolutePath(), e);
        }

        if (documents.isEmpty()) {
            log.warn("[RAG] 未发现可加载的规范文档: {}", directory.toAbsolutePath());
            return;
        }

        if (clearBeforeLoad) {
            // 可选清空旧索引，避免开发环境重复注入。
            embeddingStore.removeAll();
        }

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
            .documentSplitter(new MethodLevelDocumentSplitter())
            .embeddingModel(embeddingModel)
            .embeddingStore(embeddingStore)
            .build();

        ingestor.ingest(documents);
        log.info("[RAG] 预加载完成: directory={}, documents={}", directory.toAbsolutePath(), documents.size());
    }

    /**
     * 递归读取目录下符合扩展名约束的文档。
     */
    private List<Document> loadDocuments(Path directory, Set<String> extensions) throws IOException {
        try (Stream<Path> pathStream = Files.walk(directory)) {
            return pathStream
                .filter(Files::isRegularFile)
                .filter(path -> matchesExtension(path, extensions))
                .sorted()
                .map(this::toDocument)
                .toList();
        }
    }

    /**
     * 按文件后缀判断是否属于可索引文档。
     */
    private boolean matchesExtension(Path path, Set<String> extensions) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(extension -> fileName.endsWith("." + extension));
    }

    /**
     * 将文件内容包装成带来源信息的文档对象，便于检索结果溯源。
     */
    private Document toDocument(Path path) {
        try {
            String content = Files.readString(path);
            String text = """
                来源文件: %s

                %s
                """.formatted(path.toString(), content);
            return Document.from(text);
        } catch (IOException e) {
            throw new IllegalStateException("读取文档失败: " + path.toAbsolutePath(), e);
        }
    }

    /**
     * 解析配置中的扩展名列表，并统一转成小写集合。
     */
    private Set<String> parseExtensions(String extensions) {
        return Arrays.stream(extensions.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .map(item -> item.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    }

}
