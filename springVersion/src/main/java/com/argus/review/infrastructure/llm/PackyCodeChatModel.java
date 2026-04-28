package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.ValidationUtils;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 自定义 ChatLanguageModel，直接对接 packycode 的 /v1/chat/completions SSE 输出。
 * <p>packycode 的 gpt-5.4 强制返回 SSE，LangChain4j 原生 OpenAiChatModel 无法解析，
 * 此类通过 Java HttpClient 直接调用并逐行解析 SSE。</p>
 */
@Slf4j
public class PackyCodeChatModel implements ChatLanguageModel {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 创建同步模型客户端。
     */
    @Builder
    public PackyCodeChatModel(String baseUrl, String apiKey, String modelName,
                               Double temperature, Duration timeout) {
        this.baseUrl = ValidationUtils.ensureNotNull(baseUrl, "baseUrl");
        this.apiKey = ValidationUtils.ensureNotNull(apiKey, "apiKey");
        this.modelName = ValidationUtils.ensureNotNull(modelName, "modelName");
        this.temperature = temperature;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .build();
    }

    /**
     * 通过 packycode 接口发起一次同步对话请求。
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    /**
     * 通过 packycode 接口发起支持工具调用的对话请求。
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        try {
            String requestBody = OpenAiProtocolSupport.buildRequestBody(
                objectMapper, modelName, temperature, messages, toolSpecifications
            );
            log.debug("[PackyCode] Request body: {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + responseBody);
            }

            return OpenAiProtocolSupport.parseSseBody(objectMapper, responseBody, null);
        } catch (Exception e) {
            throw new RuntimeException("PackyCode chat completion failed", e);
        }
    }

}
