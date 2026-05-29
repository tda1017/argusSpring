package com.argus.review;

import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.agent.LogicAgent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 核心链路验证测试。
 * <p>使用 DeepSeek 官方 OpenAI 兼容接口，API Key 从环境变量注入。</p>
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ARGUS_RUN_LLM_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class LlmLinkVerificationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("langchain4j.open-ai.chat-model.api-key", () -> System.getenv("DEEPSEEK_API_KEY"));
        registry.add("langchain4j.open-ai.chat-model.base-url", () -> "https://api.deepseek.com");
        registry.add("langchain4j.open-ai.chat-model.model-name", () -> "deepseek-v4-pro");
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
