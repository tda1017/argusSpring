package com.argus.review;

import com.argus.review.infrastructure.llm.PackyCodeChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

/**
 * 用于观察 packycode 同步接口原始返回内容的调试测试。
 */
@EnabledIfEnvironmentVariable(named = "ARGUS_RUN_LLM_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class PackyCodeDebugTest {

    /**
     * 直接打印模型返回文本与 token 使用量，便于排查协议兼容问题。
     */
    @Test
    void debugRawResponse() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");

        PackyCodeChatModel model = PackyCodeChatModel.builder()
            .baseUrl("https://www.packyapi.com/v1")
            .apiKey(apiKey)
            .modelName("gpt-5.4")
            .build();

        List<ChatMessage> messages = List.of(
            SystemMessage.from("You are a security expert. Be concise."),
            UserMessage.from("Review this code for SQL injection: String sql = \"SELECT * FROM users WHERE name = '\" + input + \"'\";")
        );

        Response<AiMessage> response = model.generate(messages);
        System.out.println("=== Response text ===");
        System.out.println("[" + response.content().text() + "]");
        System.out.println("=== Token usage ===");
        System.out.println(response.tokenUsage());
    }

}
