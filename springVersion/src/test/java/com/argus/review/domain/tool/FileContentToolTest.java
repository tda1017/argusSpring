package com.argus.review.domain.tool;

import com.argus.review.application.port.out.GitHubPort;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件内容工具参数解析测试。
 */
class FileContentToolTest {

    private final StubGitHubPort gitHubPort = new StubGitHubPort();
    private final FileContentTool fileContentTool = new FileContentTool(gitHubPort);

    /**
     * 仓库名 + 文件路径形式应被正确解析。
     */
    @Test
    void shouldResolveRepositoryAndPath() {
        gitHubPort.content = "class App {}";

        String content = fileContentTool.fetchFileContent("openai/codex", "src/App.java", "main");

        assertEquals("class App {}", content);
        assertEquals("openai", gitHubPort.owner);
        assertEquals("codex", gitHubPort.repo);
        assertEquals("src/App.java", gitHubPort.path);
        assertEquals("main", gitHubPort.ref);
    }

    /**
     * 完整 GitHub 文件 URL 应被正确解析。
     */
    @Test
    void shouldResolveBlobUrl() {
        gitHubPort.content = "body";

        String content = fileContentTool.fetchFileContent(
            "https://github.com/openai/codex/blob/main/src/App.java",
            "",
            ""
        );

        assertEquals("body", content);
        assertEquals("openai", gitHubPort.owner);
        assertEquals("codex", gitHubPort.repo);
        assertEquals("src/App.java", gitHubPort.path);
        assertEquals("main", gitHubPort.ref);
    }

    /**
     * Tool schema 必须暴露稳定参数名。
     */
    @Test
    void shouldExposeStableToolParameterNames() {
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(FileContentTool.class);

        ToolSpecification fetchFileContent = specifications.stream()
            .filter(specification -> "fetchFileContent".equals(specification.name()))
            .findFirst()
            .orElseThrow();

        assertTrue(fetchFileContent.parameters().properties().containsKey("projectId"));
        assertTrue(fetchFileContent.parameters().properties().containsKey("filePath"));
        assertTrue(fetchFileContent.parameters().properties().containsKey("ref"));
        assertTrue(fetchFileContent.parameters().required().contains("projectId"));
    }

    private static final class StubGitHubPort implements GitHubPort {

        private String owner = "";
        private String repo = "";
        private String path = "";
        private String ref = "";
        private String content = "";

        @Override
        public Mono<String> fetchPrDiff(String owner, String repo, int prNumber) {
            return Mono.just("");
        }

        @Override
        public Mono<String> fetchCommitDiff(String owner, String repo, String commitSha) {
            return Mono.just("");
        }

        @Override
        public Mono<String> fetchFileContent(String owner, String repo, String path, String ref) {
            this.owner = owner;
            this.repo = repo;
            this.path = path;
            this.ref = ref;
            return Mono.just(content);
        }

        @Override
        public Mono<Long> postPrComment(String owner, String repo, int prNumber, String body) {
            return Mono.just(1L);
        }
    }
}
