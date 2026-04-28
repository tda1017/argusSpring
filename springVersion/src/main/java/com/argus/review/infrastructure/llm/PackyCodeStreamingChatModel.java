package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.ValidationUtils;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 自定义 StreamingChatLanguageModel，对接 packycode SSE 流式输出。
 */
@Slf4j
public class PackyCodeStreamingChatModel implements StreamingChatLanguageModel {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 创建流式模型客户端。
     */
    @Builder
    public PackyCodeStreamingChatModel(String baseUrl, String apiKey, String modelName,
                                        Double temperature, Duration timeout) {
        this.baseUrl = ValidationUtils.ensureNotNull(baseUrl, "baseUrl");
        this.apiKey = ValidationUtils.ensureNotNull(apiKey, "apiKey");
        this.modelName = ValidationUtils.ensureNotNull(modelName, "modelName");
        this.temperature = temperature;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(120);
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .build();
    }

    /**
     * 通过 packycode SSE 接口发起流式对话请求。
     */
    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        generate(messages, List.of(), handler);
    }

    /**
     * 通过 packycode SSE 接口发起支持工具调用的流式对话请求。
     */
    @Override
    public void generate(
        List<ChatMessage> messages,
        List<ToolSpecification> toolSpecifications,
        StreamingResponseHandler<AiMessage> handler
    ) {
        try {
            String requestBody = OpenAiProtocolSupport.buildRequestBody(
                objectMapper, modelName, temperature, messages, toolSpecifications
            );
            log.debug("Streaming request body: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + body);
            }

            parseSseStream(response.body(), handler);
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    /**
     * 逐行消费 SSE 流，并把内容回调给 LangChain4j 的流式处理器。
     */
    private void parseSseStream(java.io.InputStream inputStream, StreamingResponseHandler<AiMessage> handler) {
        OpenAiProtocolSupport.SseAccumulator accumulator = OpenAiProtocolSupport.newSseAccumulator(
            objectMapper, handler::onNext
        );

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!accumulator.consumeLine(line)) {
                    break;
                }
            }

            handler.onComplete(accumulator.complete());

        } catch (Exception e) {
            handler.onError(e);
        }
    }

}
