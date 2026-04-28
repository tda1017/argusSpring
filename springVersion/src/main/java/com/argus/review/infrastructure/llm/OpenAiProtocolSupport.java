package com.argus.review.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions 协议适配工具。
 */
final class OpenAiProtocolSupport {

    private OpenAiProtocolSupport() {
    }

    static String buildRequestBody(
        ObjectMapper objectMapper,
        String modelName,
        Double temperature,
        List<ChatMessage> messages,
        List<ToolSpecification> toolSpecifications
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", true);
        // gpt-5.4 等 reasoning 模型通常不支持 temperature 参数。
        if (temperature != null && !modelName.contains("gpt-5")) {
            root.put("temperature", temperature);
        }

        ArrayNode messageNodes = root.putArray("messages");
        for (ChatMessage message : messages) {
            appendMessage(messageNodes, message);
        }

        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpecification specification : toolSpecifications) {
                tools.add(toOpenAiTool(objectMapper, specification));
            }
            root.put("tool_choice", "auto");
        }

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static SseAccumulator newSseAccumulator(ObjectMapper objectMapper, Consumer<String> tokenConsumer) {
        return new SseAccumulator(objectMapper, tokenConsumer);
    }

    static Response<AiMessage> parseSseBody(
        ObjectMapper objectMapper,
        String body,
        Consumer<String> tokenConsumer
    ) {
        SseAccumulator accumulator = newSseAccumulator(objectMapper, tokenConsumer);
        for (String line : body.split("\n")) {
            if (!accumulator.consumeLine(line)) {
                break;
            }
        }
        return accumulator.complete();
    }

    private static void appendMessage(ArrayNode messages, ChatMessage message) {
        ObjectNode node = messages.addObject();
        switch (message.type()) {
            case SYSTEM -> {
                node.put("role", "system");
                node.put("content", message.text());
            }
            case USER -> {
                node.put("role", "user");
                node.put("content", message.text());
            }
            case AI -> appendAiMessage(node, (AiMessage) message);
            case TOOL_EXECUTION_RESULT -> appendToolResultMessage(node, (ToolExecutionResultMessage) message);
            default -> {
                node.put("role", "user");
                node.put("content", message.text());
            }
        }
    }

    private static void appendAiMessage(ObjectNode node, AiMessage message) {
        node.put("role", "assistant");
        if (!message.hasToolExecutionRequests()) {
            node.put("content", message.text());
            return;
        }

        if (message.text() == null || message.text().isBlank()) {
            node.putNull("content");
        } else {
            node.put("content", message.text());
        }

        ArrayNode toolCalls = node.putArray("tool_calls");
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            ObjectNode toolCall = toolCalls.addObject();
            toolCall.put("id", request.id());
            toolCall.put("type", "function");
            ObjectNode function = toolCall.putObject("function");
            function.put("name", request.name());
            function.put("arguments", request.arguments());
        }
    }

    private static void appendToolResultMessage(ObjectNode node, ToolExecutionResultMessage message) {
        node.put("role", "tool");
        node.put("tool_call_id", message.id());
        node.put("name", message.toolName());
        node.put("content", message.text());
    }

    private static ObjectNode toOpenAiTool(ObjectMapper objectMapper, ToolSpecification specification) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", specification.name());
        if (specification.description() != null && !specification.description().isBlank()) {
            function.put("description", specification.description());
        }
        function.set("parameters", toParameters(objectMapper, specification));
        return tool;
    }

    private static ObjectNode toParameters(ObjectMapper objectMapper, ToolSpecification specification) {
        if (specification.parameters() != null) {
            return toJsonSchema(objectMapper, specification.parameters());
        }
        if (specification.toolParameters() != null) {
            return toLegacyParameters(objectMapper, specification.toolParameters());
        }
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", objectMapper.createObjectNode());
        return parameters;
    }

    private static ObjectNode toLegacyParameters(ObjectMapper objectMapper, ToolParameters toolParameters) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", toolParameters.type() == null ? "object" : toolParameters.type());
        parameters.set("properties", objectMapper.valueToTree(toolParameters.properties()));
        if (toolParameters.required() != null && !toolParameters.required().isEmpty()) {
            ArrayNode required = parameters.putArray("required");
            toolParameters.required().forEach(required::add);
        }
        return parameters;
    }

    private static ObjectNode toJsonSchema(ObjectMapper objectMapper, JsonObjectSchema schema) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "object");
        putDescription(node, schema.description());

        ObjectNode properties = objectMapper.createObjectNode();
        if (schema.properties() != null) {
            schema.properties().forEach((name, value) -> properties.set(name, toJsonSchemaElement(objectMapper, value)));
        }
        node.set("properties", properties);

        if (schema.required() != null && !schema.required().isEmpty()) {
            ArrayNode required = node.putArray("required");
            schema.required().forEach(required::add);
        }
        if (schema.additionalProperties() != null) {
            node.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            ObjectNode definitions = node.putObject("definitions");
            schema.definitions().forEach((name, value) -> definitions.set(name, toJsonSchemaElement(objectMapper, value)));
        }
        return node;
    }

    private static JsonNode toJsonSchemaElement(ObjectMapper objectMapper, JsonSchemaElement element) {
        if (element instanceof JsonObjectSchema objectSchema) {
            return toJsonSchema(objectMapper, objectSchema);
        }
        if (element instanceof JsonStringSchema stringSchema) {
            ObjectNode node = typedNode(objectMapper, "string");
            putDescription(node, stringSchema.description());
            return node;
        }
        if (element instanceof JsonIntegerSchema integerSchema) {
            ObjectNode node = typedNode(objectMapper, "integer");
            putDescription(node, integerSchema.description());
            return node;
        }
        if (element instanceof JsonNumberSchema numberSchema) {
            ObjectNode node = typedNode(objectMapper, "number");
            putDescription(node, numberSchema.description());
            return node;
        }
        if (element instanceof JsonBooleanSchema booleanSchema) {
            ObjectNode node = typedNode(objectMapper, "boolean");
            putDescription(node, booleanSchema.description());
            return node;
        }
        if (element instanceof JsonEnumSchema enumSchema) {
            ObjectNode node = typedNode(objectMapper, "string");
            putDescription(node, enumSchema.description());
            ArrayNode values = node.putArray("enum");
            if (enumSchema.enumValues() != null) {
                enumSchema.enumValues().forEach(values::add);
            }
            return node;
        }
        if (element instanceof JsonArraySchema arraySchema) {
            ObjectNode node = typedNode(objectMapper, "array");
            putDescription(node, arraySchema.description());
            if (arraySchema.items() != null) {
                node.set("items", toJsonSchemaElement(objectMapper, arraySchema.items()));
            }
            return node;
        }
        if (element instanceof JsonReferenceSchema referenceSchema) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("$ref", referenceSchema.reference());
            return node;
        }
        return typedNode(objectMapper, "string");
    }

    private static ObjectNode typedNode(ObjectMapper objectMapper, String type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static void putDescription(ObjectNode node, String description) {
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        }
    }

    static final class SseAccumulator {

        private final ObjectMapper objectMapper;
        private final Consumer<String> tokenConsumer;
        private final StringBuilder contentBuilder = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        private int promptTokens;
        private int completionTokens;

        private SseAccumulator(ObjectMapper objectMapper, Consumer<String> tokenConsumer) {
            this.objectMapper = objectMapper;
            this.tokenConsumer = tokenConsumer == null ? token -> {} : tokenConsumer;
        }

        boolean consumeLine(String rawLine) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                return true;
            }

            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) {
                return false;
            }

            try {
                consumeData(data);
            } catch (Exception ignored) {
                return true;
            }
            return true;
        }

        Response<AiMessage> complete() {
            List<ToolExecutionRequest> requests = toolCalls.entrySet().stream()
                .map(entry -> entry.getValue().toRequest(entry.getKey()))
                .filter(request -> request.name() != null && !request.name().isBlank())
                .toList();

            AiMessage message;
            String text = contentBuilder.toString();
            if (requests.isEmpty()) {
                message = AiMessage.from(text);
            } else if (text.isBlank()) {
                message = AiMessage.from(requests);
            } else {
                message = AiMessage.from(text, requests);
            }

            return Response.from(message, new TokenUsage(promptTokens, completionTokens), null);
        }

        private void consumeData(String data) throws Exception {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    consumeAssistantPatch(choice.path("delta"));
                    consumeAssistantPatch(choice.path("message"));
                }
            }
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
            }
        }

        private void consumeAssistantPatch(JsonNode patch) {
            if (patch.isMissingNode() || patch.isNull()) {
                return;
            }
            if (patch.has("content") && !patch.path("content").isNull()) {
                String chunk = patch.path("content").asText("");
                if (!chunk.isEmpty()) {
                    tokenConsumer.accept(chunk);
                    contentBuilder.append(chunk);
                }
            }
            JsonNode toolCallNodes = patch.path("tool_calls");
            if (toolCallNodes.isArray()) {
                for (JsonNode toolCallNode : toolCallNodes) {
                    int index = toolCallNode.has("index") ? toolCallNode.path("index").asInt() : toolCalls.size();
                    ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                    accumulator.accept(toolCallNode);
                }
            }
        }
    }

    private static final class ToolCallAccumulator {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void accept(JsonNode toolCallNode) {
            if (toolCallNode.has("id")) {
                id = toolCallNode.path("id").asText(id);
            }
            JsonNode function = toolCallNode.path("function");
            if (function.has("name")) {
                name = function.path("name").asText(name);
            }
            if (function.has("arguments")) {
                arguments.append(function.path("arguments").asText(""));
            }
        }

        private ToolExecutionRequest toRequest(int index) {
            return ToolExecutionRequest.builder()
                .id(id == null || id.isBlank() ? "tool-call-" + index : id)
                .name(name)
                .arguments(arguments.isEmpty() ? "{}" : arguments.toString())
                .build();
        }
    }
}
