package com.argus.review;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 验证 OpenAI 兼容流式模型能否正常接收 DeepSeek SSE 输出。
 */
@EnabledIfEnvironmentVariable(named = "ARGUS_RUN_LLM_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class Gpt54StreamingTest {

    @Test
    void testStreaming() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");

        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
            .baseUrl("https://api.deepseek.com")
            .apiKey(apiKey)
            .modelName("deepseek-v4-pro")
            .reasoningEffort("high")
            .sendThinking(true, "enabled")
            .build();

        List<ChatMessage> messages = List.of(
            SystemMessage.from("You are a security expert. Be concise."),
            UserMessage.from("Review this code for SQL injection: String sql = \"SELECT * FROM users WHERE name = '\" + input + \"'\";")
        );

        StringBuilder sb = new StringBuilder();
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                sb.append(token);
                System.out.print(token);
                System.out.flush();
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                System.out.println("\n\n[COMPLETE] Tokens: " + response.tokenUsage());
                future.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        future.join();
        System.out.println("\n\n=== Full response ===");
        System.out.println(sb.toString());
    }

}
