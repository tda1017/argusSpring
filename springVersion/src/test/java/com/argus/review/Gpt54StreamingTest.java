package com.argus.review;

import com.argus.review.infrastructure.llm.PackyCodeStreamingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 验证 PackyCodeStreamingChatModel 能否正常接收 packycode 的 SSE 输出。
 */
@EnabledIfEnvironmentVariable(named = "ARGUS_RUN_LLM_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class Gpt54StreamingTest {

    @Test
    void testStreaming() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");

        PackyCodeStreamingChatModel model = PackyCodeStreamingChatModel.builder()
            .baseUrl("https://www.packyapi.com/v1")
            .apiKey(apiKey)
            .modelName("gpt-5.4")
            .build();

        List<ChatMessage> messages = List.of(
            SystemMessage.from("You are a security expert. Be concise."),
            UserMessage.from("Review this code for SQL injection: String sql = \"SELECT * FROM users WHERE name = '\" + input + \"'\";")
        );

        StringBuilder sb = new StringBuilder();
        CompletableFuture<Response<AiMessage>> future = new CompletableFuture<>();

        model.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                sb.append(token);
                System.out.print(token);
                System.out.flush();
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
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
