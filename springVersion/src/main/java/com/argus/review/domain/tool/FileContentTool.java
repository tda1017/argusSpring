package com.argus.review.domain.tool;

import com.argus.review.application.port.out.GitHubPort;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 * GitHub 文件内容读取工具。
 * <p>供 FixAgent 在 Diff 上下文不足时拉取目标文件原文。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileContentTool {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);

    private final GitHubPort gitHubPort;

    /**
     * 获取 GitHub 仓库中文件的原始文本内容。
     *
     * @param projectId 仓库路径 owner/repo，或完整 GitHub 文件 URL
     * @param filePath  文件路径；projectId 为完整 URL 时可为空
     * @param ref       分支、标签或 Commit SHA；可为空
     * @return 文件文本内容
     */
    @Tool("从 GitHub 获取仓库中文件的原始内容。当修复补丁需要完整文件上下文时使用此工具。")
    public String fetchFileContent(
        @P(value = "GitHub 仓库路径 owner/repo，或完整文件 URL", required = true) String projectId,
        @P(value = "仓库内文件路径；如果 projectId 是完整文件 URL，可为空", required = false) String filePath,
        @P(value = "分支、标签或 Commit SHA；可为空", required = false) String ref
    ) {
        FileTarget target = resolveFileTarget(projectId, filePath, ref);
        log.info("[Tool] 正在获取 GitHub 文件内容: {}/{}/{}@{}",
            target.owner(), target.repo(), target.path(), target.ref());

        return gitHubPort.fetchFileContent(target.owner(), target.repo(), target.path(), target.ref())
            .block(API_TIMEOUT);
    }

    /**
     * 兼容 owner/repo + path 和 GitHub blob/raw URL 两种输入。
     */
    private FileTarget resolveFileTarget(String projectId, String filePath, String ref) {
        if (isGitHubUrl(projectId)) {
            FileTarget fromUrl = parseFileUrl(projectId);
            if (filePath == null || filePath.isBlank()) {
                return withRef(fromUrl, ref);
            }
            String normalizedRef = blankToNull(ref);
            return new FileTarget(
                fromUrl.owner(),
                fromUrl.repo(),
                filePath.trim(),
                normalizedRef == null ? fromUrl.ref() : normalizedRef
            );
        }

        RepositoryTarget repository = parseRepository(projectId);
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath 不能为空");
        }
        return new FileTarget(repository.owner(), repository.repo(), filePath.trim(), blankToNull(ref));
    }

    /**
     * 解析 GitHub blob/raw URL。
     */
    private FileTarget parseFileUrl(String url) {
        URI uri = URI.create(url.trim());
        String[] parts = Arrays.stream(uri.getPath().split("/"))
            .filter(part -> !part.isBlank())
            .toArray(String[]::new);
        if (parts.length < 5 || (!"blob".equals(parts[2]) && !"raw".equals(parts[2]))) {
            throw new IllegalArgumentException("暂不支持的文件 URL: " + url);
        }
        String path = String.join("/", Arrays.copyOfRange(parts, 4, parts.length));
        return new FileTarget(parts[0], parts[1], path, parts[3]);
    }

    /**
     * 解析 `owner/repo` 形式的仓库标识。
     */
    private RepositoryTarget parseRepository(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空，格式应为 owner/repo");
        }
        String normalized = projectId.trim();
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        String[] parts = normalized.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("projectId 格式错误，应为 owner/repo");
        }
        return new RepositoryTarget(parts[0].trim(), parts[1].trim());
    }

    private boolean isGitHubUrl(String value) {
        return value != null && value.contains("github.com/");
    }

    private FileTarget withRef(FileTarget target, String ref) {
        String normalizedRef = blankToNull(ref);
        if (normalizedRef == null) {
            return target;
        }
        return new FileTarget(target.owner(), target.repo(), target.path(), normalizedRef);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RepositoryTarget(String owner, String repo) {}

    private record FileTarget(String owner, String repo, String path, String ref) {}

}
