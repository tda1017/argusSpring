package com.argus.review.infrastructure.config;

import com.argus.review.infrastructure.llm.PackyCodeChatModel;
import com.argus.review.infrastructure.llm.PackyCodeStreamingChatModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * LangChain4j 基础设施配置。
 * <p>使用自定义 PackyCodeChatModel 替代原生 OpenAiChatModel，
 * 以兼容 packycode 强制 SSE 输出的 gpt-5.4 模型。</p>
 */
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.open-ai.chat-model.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:gpt-5.4}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.2}")
    private Double temperature;

    @Value("${argus.rag.milvus.host:localhost}")
    private String milvusHost;

    @Value("${argus.rag.milvus.port:19530}")
    private Integer milvusPort;

    @Value("${argus.rag.milvus.collection-name:code_review_specs}")
    private String milvusCollectionName;

    @Value("${argus.rag.milvus.dimension:384}")
    private Integer milvusDimension;

    @Value("${argus.rag.milvus.index-type:AUTOINDEX}")
    private String milvusIndexType;

    @Value("${argus.rag.milvus.metric-type:COSINE}")
    private String milvusMetricType;

    @Value("${argus.rag.milvus.consistency-level:BOUNDED}")
    private String milvusConsistencyLevel;

    @Value("${argus.rag.milvus.database-name:}")
    private String milvusDatabaseName;

    @Value("${argus.rag.milvus.username:}")
    private String milvusUsername;

    @Value("${argus.rag.milvus.password:}")
    private String milvusPassword;

    @Value("${argus.rag.milvus.token:}")
    private String milvusToken;

    /**
     * 同步对话模型，供一次性审查调用。
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return PackyCodeChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    /**
     * 流式对话模型，供 SSE 审查接口逐 token 推送。
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return PackyCodeStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(120))
            .build();
    }

    /**
     * 本地嵌入模型，避免把向量化流程绑死到远程服务。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        // 本地 ONNX Embedding 模型，无需远程 API Key，适合离线环境
        return new BgeSmallEnV15QuantizedEmbeddingModel();
    }

    /**
     * 内存级向量存储（用于开发/测试环境）。
     */
    @Bean
    @Profile("!prod")
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 生产环境向量存储：Milvus。
     */
    @Bean
    @Profile("prod")
    public EmbeddingStore<TextSegment> milvusEmbeddingStore() {
        // Milvus 参数集中在这里收口，避免散落在业务代码里。
        MilvusEmbeddingStore.Builder builder = MilvusEmbeddingStore.builder()
            .host(milvusHost)
            .port(milvusPort)
            .collectionName(milvusCollectionName)
            .dimension(milvusDimension)
            .indexType(IndexType.valueOf(milvusIndexType.toUpperCase()))
            .metricType(MetricType.valueOf(milvusMetricType.toUpperCase()))
            .consistencyLevel(ConsistencyLevelEnum.valueOf(milvusConsistencyLevel.toUpperCase()))
            .retrieveEmbeddingsOnSearch(false)
            .autoFlushOnInsert(true);

        if (!milvusDatabaseName.isBlank()) {
            builder.databaseName(milvusDatabaseName);
        }
        if (!milvusUsername.isBlank()) {
            builder.username(milvusUsername);
        }
        if (!milvusPassword.isBlank()) {
            builder.password(milvusPassword);
        }
        if (!milvusToken.isBlank()) {
            builder.token(milvusToken);
        }

        return builder.build();
    }

}
