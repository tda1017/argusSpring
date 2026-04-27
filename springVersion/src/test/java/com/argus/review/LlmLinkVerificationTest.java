package com.argus.review;

import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.agent.LogicAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 核心链路验证测试。
 * <p>自动从 ~/.codex/auth.json 读取 API Key（若环境变量未设置）。</p>
 */
@Slf4j
@SpringBootTest
class LlmLinkVerificationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String envKey = System.getenv("OPENAI_API_KEY");
        final String apiKey;
        if (envKey != null && !envKey.isBlank()) {
            apiKey = envKey;
        } else {
            String fileKey = "";
            try {
                fileKey = new ObjectMapper().readTree(
                    Path.of(System.getProperty("user.home"), ".codex", "auth.json").toFile()
                ).path("OPENAI_API_KEY").asText("");
            } catch (Exception e) {
                log.warn("Failed to read API key from ~/.codex/auth.json", e);
            }
            apiKey = fileKey;
        }
        if (!apiKey.isBlank()) {
            registry.add("langchain4j.open-ai.chat-model.api-key", () -> apiKey);
        }
    }

    @Autowired
    private SecurityAgent securityAgent;

    @Autowired
    private StyleAgent styleAgent;

    @Autowired
    private LogicAgent logicAgent;

    private static final String SAMPLE_DIFF = """
        + public void processUserInput(String input) {
        +     String sql = "SELECT * FROM users WHERE name = '" + input + "'";
        +     jdbcTemplate.execute(sql);
        + }
        """;

    @Test
    void verifySecurityAgent() {
        String result = securityAgent.review(SAMPLE_DIFF, "暂无相关内部规范");
        log.info("SecurityAgent output:\n{}", result);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void verifyStyleAgent() {
        String result = styleAgent.review(SAMPLE_DIFF, "暂无相关内部规范");
        log.info("StyleAgent output:\n{}", result);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void verifyLogicAgent() {
        String result = logicAgent.review(SAMPLE_DIFF, "暂无相关内部规范");
        log.info("LogicAgent output:\n{}", result);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

}
