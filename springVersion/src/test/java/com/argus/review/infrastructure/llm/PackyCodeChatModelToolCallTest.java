package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PackyCode tool-calling 协议适配测试。
 */
class PackyCodeChatModelToolCallTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 模型请求必须携带 tools，响应中的 tool_calls 必须还原为 LangChain4j 请求对象。
     */
    @Test
    void shouldSendToolSpecificationsAndParseToolCalls() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        String toolArguments = "{\\\"projectId\\\":\\\"openai/codex\\\",\\\"mrId\\\":\\\"42\\\"}";
        String responseBody = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"fetchMrDiff","arguments":"%s"}}]}}],"usage":{"prompt_tokens":11,"completion_tokens":2}}

            data: [DONE]

            """.formatted(toolArguments);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        PackyCodeChatModel model = PackyCodeChatModel.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .apiKey("test-key")
            .modelName("gpt-5.4")
            .build();

        Response<AiMessage> response = model.generate(
            List.of(UserMessage.from("审查 https://github.com/openai/codex/pull/42")),
            List.of(fetchMrDiffSpec())
        );

        JsonNode requestJson = objectMapper.readTree(capturedRequest.get());
        JsonNode function = requestJson.path("tools").get(0).path("function");
        assertEquals("fetchMrDiff", function.path("name").asText());
        assertEquals("object", function.path("parameters").path("type").asText());
        assertTrue(function.path("parameters").path("properties").has("projectId"));

        assertTrue(response.content().hasToolExecutionRequests());
        ToolExecutionRequest request = response.content().toolExecutionRequests().get(0);
        assertEquals("call_1", request.id());
        assertEquals("fetchMrDiff", request.name());
        assertEquals("{\"projectId\":\"openai/codex\",\"mrId\":\"42\"}", request.arguments());
    }

    private ToolSpecification fetchMrDiffSpec() {
        JsonObjectSchema parameters = JsonObjectSchema.builder()
            .properties(Map.of(
                "projectId", JsonStringSchema.builder().description("GitHub repository").build(),
                "mrId", JsonStringSchema.builder().description("Pull Request number").build()
            ))
            .required(List.of("projectId", "mrId"))
            .build();

        return ToolSpecification.builder()
            .name("fetchMrDiff")
            .description("Fetch GitHub Pull Request diff")
            .parameters(parameters)
            .build();
    }
}
