package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.ValidationUtils;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
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
        try {
            String requestBody = buildRequestBody(messages);
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

            return parseSseResponse(responseBody);
        } catch (Exception e) {
            throw new RuntimeException("PackyCode chat completion failed", e);
        }
    }

    /**
     * 按 OpenAI Chat Completions 协议拼装请求体。
     */
    private String buildRequestBody(List<ChatMessage> messages) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        // packycode 的 gpt-5.4 强制要求 stream=true，否则返回 content: null
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
     * 解析 SSE 文本响应，并拼接成 LangChain4j 需要的完整消息对象。
     */
    private Response<AiMessage> parseSseResponse(String body) {
        StringBuilder contentBuilder = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;

        for (String line : body.split("\n")) {
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
                        contentBuilder.append(chunk);
                    }
                }
                // usage 可能只在尾块出现，所以每次都尝试覆盖最新值。
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
        return Response.from(aiMessage, tokenUsage, null);
    }

}
