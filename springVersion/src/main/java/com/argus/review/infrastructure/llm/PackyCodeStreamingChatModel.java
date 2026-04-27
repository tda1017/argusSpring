package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.ValidationUtils;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
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
        try {
            String requestBody = buildRequestBody(messages);
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
     * 按 OpenAI Chat Completions 协议拼装流式请求体。
     */
    private String buildRequestBody(List<ChatMessage> messages) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", true);
        // gpt-5.4 等 reasoning 模型通常不支持 temperature 参数
        if (temperature != null && !modelName.contains("gpt-5")) {
            root.put("temperature", temperature);
        }

        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode node = msgs.addObject();
            switch (msg.type()) {
                case SYSTEM -> {
                    node.put("role", "system");
                    node.put("content", msg.text());
                }
                case USER -> {
                    node.put("role", "user");
                    node.put("content", msg.text());
                }
                case AI -> {
                    node.put("role", "assistant");
                    node.put("content", msg.text());
                }
                default -> {
                    node.put("role", "user");
                    node.put("content", msg.text());
                }
            }
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 逐行消费 SSE 流，并把内容回调给 LangChain4j 的流式处理器。
     */
    private void parseSseStream(java.io.InputStream inputStream, StreamingResponseHandler<AiMessage> handler) {
        StringBuilder contentBuilder = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JsonNode root = objectMapper.readTree(data);
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        if (delta.has("content")) {
                            String chunk = delta.path("content").asText("");
                            if (!chunk.isEmpty()) {
                                // 先推给上游，再累计完整文本，保证前端尽快收到 token。
                                handler.onNext(chunk);
                                contentBuilder.append(chunk);
                            }
                        }
                    }
                    if (root.has("usage")) {
                        JsonNode usage = root.path("usage");
                        promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                        completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse SSE chunk: {}", data, e);
                }
            }

            String fullText = contentBuilder.toString();
            AiMessage aiMessage = AiMessage.from(fullText);
            TokenUsage tokenUsage = new TokenUsage(promptTokens, completionTokens);
            handler.onComplete(Response.from(aiMessage, tokenUsage, null));

        } catch (Exception e) {
            handler.onError(e);
        }
    }

}
