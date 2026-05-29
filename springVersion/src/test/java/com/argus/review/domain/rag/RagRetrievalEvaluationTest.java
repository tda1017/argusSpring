package com.argus.review.domain.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索评估。
 * <p>默认不运行，避免每次单元测试都做向量评估。</p>
 */
@SpringBootTest(properties = "argus.rag.preload.enabled=false")
@EnabledIfSystemProperty(named = "argus.rag.eval.enabled", matches = "true")
class RagRetrievalEvaluationTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @BeforeEach
    void clearStore() {
        embeddingStore.removeAll();
    }

    /**
     * 按标注数据计算 recall@3。
     */
    @Test
    void shouldEvaluateRecallAt3() throws IOException {
        Path dataset = Path.of(System.getProperty(
            "argus.rag.eval.dataset",
            "docs/rag-evaluation-dataset.csv"
        ));
        double minRecall = Double.parseDouble(System.getProperty("argus.rag.eval.min-recall", "0.0"));
        RetrievalService retrievalService = buildRetrievalService();

        List<EvaluationCase> cases = loadDataset(dataset);
        assertFalse(cases.isEmpty(), "RAG 评估数据集不能为空");

        int hits = 0;
        for (EvaluationCase evaluationCase : cases) {
            String context = retrievalService.retrieveRelevantContext(evaluationCase.query(), 3, 0.0);
            boolean hit = context.contains(evaluationCase.expectedSource());
            if (hit) {
                hits++;
            }
            System.out.printf(
                "rag_eval query=\"%s\" expected=\"%s\" hit=%s%n",
                evaluationCase.query(),
                evaluationCase.expectedSource(),
                hit
            );
        }

        double recallAt3 = hits / (double) cases.size();
        System.out.printf("rag_eval summary total=%d hits=%d recall@3=%.4f%n", cases.size(), hits, recallAt3);
        assertTrue(
            recallAt3 >= minRecall,
            "recall@3 %.4f 低于阈值 %.4f".formatted(recallAt3, minRecall)
        );
    }

    /**
     * 读取简单 CSV：query,expected_source。
     */
    private List<EvaluationCase> loadDataset(Path dataset) throws IOException {
        return Files.readAllLines(dataset).stream()
            .skip(1)
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .map(line -> {
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("非法评估数据行: " + line);
                }
                return new EvaluationCase(parts[0].trim(), parts[1].trim());
            })
            .toList();
    }

    /**
     * 直接构造检索链路，不启动 Spring，评估就该少依赖。
     */
    private RetrievalService buildRetrievalService() throws IOException {
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
            .documentSplitter(new MethodLevelDocumentSplitter())
            .embeddingModel(embeddingModel)
            .embeddingStore(embeddingStore)
            .build();

        ingestor.ingest(loadSpecDocuments(Path.of("docs/specs")));
        return new RetrievalService(embeddingStore, embeddingModel);
    }

    /**
     * 读取 specs 文档并保留来源文件头，便于用 expected_source 判断命中。
     */
    private List<Document> loadSpecDocuments(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .sorted(Comparator.comparing(Path::toString))
                .map(path -> {
                    try {
                        return Document.from("""
                            来源文件: %s

                            %s
                            """.formatted(path.toString(), Files.readString(path)));
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 RAG 文档失败: " + path, e);
                    }
                })
                .toList();
        }
    }

    private record EvaluationCase(String query, String expectedSource) {}
}
