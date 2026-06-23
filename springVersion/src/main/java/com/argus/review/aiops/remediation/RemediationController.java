package com.argus.review.aiops.remediation;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 自愈审计查询接口。
 */
@RestController
@RequestMapping("/api/remediations")
@RequiredArgsConstructor
public class RemediationController {

    private final RemediationService remediationService;

    @GetMapping
    public Flux<RemediationAuditRecord> list() {
        return remediationService.recentAudits();
    }

    @GetMapping("/actions")
    public Flux<RemediationActionRecord> actions() {
        return remediationService.recentActions();
    }

    @PostMapping("/{actionId}/approve")
    public reactor.core.publisher.Mono<RemediationAction> approve(@PathVariable String actionId) {
        return remediationService.approve(actionId);
    }

    @PostMapping("/{actionId}/reject")
    public reactor.core.publisher.Mono<RemediationAction> reject(@PathVariable String actionId) {
        return remediationService.reject(actionId);
    }
}
