package com.argus.review.interfaces.web;

import com.argus.review.application.port.in.ReviewUseCase;
import com.argus.review.application.port.in.ReviewUseCase.FixCommand;
import com.argus.review.application.port.in.ReviewUseCase.FixResult;
import com.argus.review.application.port.in.ReviewUseCase.OrchestratedReviewCommand;
import com.argus.review.application.port.in.ReviewUseCase.OrchestratedReviewResult;
import com.argus.review.application.port.in.ReviewUseCase.ReviewResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 代码审查 REST / SSE 接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewUseCase reviewUseCase;

    /**
     * 同步审查接口：聚合多 Agent 结果一次性返回。
     */
    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewResult reviewSync(@Valid @RequestBody ReviewRequest request) {
        log.info("收到同步审查请求: project={}, mrId={}", request.projectId(), request.mrId());
        return reviewUseCase.review(request.codeDiff());
    }

    /**
     * 流式审查接口：通过 SSE 逐 Token 推送 AI 生成内容。
     */
    @PostMapping(
        value = "/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> reviewStream(@Valid @RequestBody ReviewRequest request) {
        log.info("收到流式审查请求: project={}, mrId={}", request.projectId(), request.mrId());
        return reviewUseCase.reviewStream(request.codeDiff())
            .onErrorResume(e -> {
                log.error("SSE 输出异常", e);
                return Flux.just("data: [ERROR] " + e.getMessage() + "\n\n");
            });
    }

    /**
     * 自动修复接口：基于 Diff 和审查报告生成 unified diff patch。
     */
    @PostMapping(value = "/fix", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FixResult fix(@Valid @RequestBody FixRequest request) {
        log.info("收到自动修复请求: project={}, mrId={}", request.projectId(), request.mrId());
        return reviewUseCase.fix(new FixCommand(
            request.projectId(),
            request.mrId(),
            request.codeDiff(),
            request.reviewReport()
        ));
    }

    /**
     * 链式协同审查接口：审查 Agent 完成后由 Orchestrator 路由给 FixAgent。
     */
    @PostMapping(value = "/orchestrated", consumes = MediaType.APPLICATION_JSON_VALUE)
    public OrchestratedReviewResult reviewOrchestrated(@Valid @RequestBody ReviewRequest request) {
        log.info("收到链式协同审查请求: project={}, mrId={}", request.projectId(), request.mrId());
        return reviewUseCase.reviewOrchestrated(new OrchestratedReviewCommand(
            request.projectId(),
            request.mrId(),
            request.codeDiff()
        ));
    }

    /**
     * 审查请求 DTO。
     */
    public record ReviewRequest(
        String projectId,
        String mrId,
        // 直接要求调用方传入 Diff，控制器不负责外部平台拉取逻辑。
        @NotBlank(message = "codeDiff 不能为空")
        String codeDiff
    ) {}

    /**
     * 自动修复请求 DTO。
     */
    public record FixRequest(
        String projectId,
        String mrId,
        @NotBlank(message = "codeDiff 不能为空")
        String codeDiff,
        String reviewReport
    ) {}

}
