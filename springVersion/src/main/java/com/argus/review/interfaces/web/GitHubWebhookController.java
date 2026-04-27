package com.argus.review.interfaces.web;

import com.argus.review.application.service.GitHubReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;

/**
 * GitHub Webhook 接收端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final GitHubReviewService gitHubReviewService;

    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    private static final Set<String> INTERESTING_ACTIONS = Set.of("opened", "synchronize", "reopened");

    /**
     * 接收 GitHub Webhook，只处理需要触发代码审查的 PR 事件。
     */
    @PostMapping("/webhook")
    public Mono<ResponseEntity<String>> handleWebhook(
        @RequestHeader("X-GitHub-Event") String eventType,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestBody String rawBody
    ) {
        // 可选：验证 Webhook 签名
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (!isValidSignature(rawBody, signature)) {
                log.warn("[GitHubWebhook] Invalid signature");
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized"));
            }
        }

        if (!"pull_request".equals(eventType)) {
            log.debug("[GitHubWebhook] Ignoring event type: {}", eventType);
            return Mono.just(ResponseEntity.ok("Ignored"));
        }

        JsonNode payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
        } catch (Exception e) {
            log.error("[GitHubWebhook] Failed to parse payload", e);
            return Mono.just(ResponseEntity.badRequest().body("Invalid JSON"));
        }

        String action = payload.path("action").asText("");
        if (!INTERESTING_ACTIONS.contains(action)) {
            log.debug("[GitHubWebhook] Ignoring action: {}", action);
            return Mono.just(ResponseEntity.ok("Ignored"));
        }

        String repoFullName = payload.path("repository").path("full_name").asText("");
        int prNumber = payload.path("number").asInt(0);

        if (repoFullName.isBlank() || prNumber == 0) {
            log.warn("[GitHubWebhook] Missing repository or PR number");
            return Mono.just(ResponseEntity.badRequest().body("Missing repository or PR number"));
        }

        String[] parts = repoFullName.split("/");
        if (parts.length != 2) {
            log.warn("[GitHubWebhook] Invalid repository format: {}", repoFullName);
            return Mono.just(ResponseEntity.badRequest().body("Invalid repository format"));
        }

        String owner = parts[0];
        String repo = parts[1];

        log.info("[GitHubWebhook] Received PR event: {}/{} #{} action={}", owner, repo, prNumber, action);

        // 异步执行审查，立即返回 202 Accepted
        gitHubReviewService.reviewPullRequest(owner, repo, prNumber)
            .subscribe(
                id -> log.info("[GitHubWebhook] Async review completed for PR #{}, commentId={}", prNumber, id),
                err -> log.error("[GitHubWebhook] Async review failed for PR #{}", prNumber, err)
            );

        return Mono.just(ResponseEntity.accepted().body("Review queued"));
    }

    /**
     * 校验 GitHub Webhook 签名，防止伪造请求直接撞进来。
     */
    private boolean isValidSignature(String payload, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return expected.equals(signatureHeader);
        } catch (Exception e) {
            log.error("[GitHubWebhook] Signature validation error", e);
            return false;
        }
    }

}
