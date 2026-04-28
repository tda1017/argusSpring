package com.argus.review;

import com.argus.review.domain.agent.LogicAgent;
import com.argus.review.domain.agent.SecurityAgent;
import com.argus.review.domain.agent.StyleAgent;
import com.argus.review.domain.tool.GitDiffTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring Bean 装配冒烟测试。
 */
@SpringBootTest(properties = {
    "argus.rag.preload.enabled=false",
    "langchain4j.open-ai.chat-model.api-key=test-key"
})
class ApplicationContextWiringTest {

    @Autowired
    private SecurityAgent securityAgent;

    @Autowired
    private StyleAgent styleAgent;

    @Autowired
    private LogicAgent logicAgent;

    @Autowired
    private GitDiffTool gitDiffTool;

    /**
     * Agent 与 GitDiffTool 必须能同时装配，防止 @AiService tools 配置漂移。
     */
    @Test
    void shouldWireAgentsWithGitDiffTool() {
        assertNotNull(securityAgent);
        assertNotNull(styleAgent);
        assertNotNull(logicAgent);
        assertNotNull(gitDiffTool);
    }
}
