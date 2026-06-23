package com.argus.review.aiops.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 演示环境最小 token 保护。未配置 token 时关闭。
 */
@Component
public class DemoTokenFilter implements WebFilter {

    private static final List<String> PROTECTED_PREFIXES = List.of(
        "/api/alerts",
        "/api/diagnosis",
        "/api/remediations",
        "/api/memory"
    );

    @Value("${argus.aiops.demo-token:}")
    private String demoToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (demoToken == null || demoToken.isBlank() || !protectedPath(exchange)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst("X-Demo-Token");
        if (token == null || token.isBlank()) {
            token = exchange.getRequest().getQueryParams().getFirst("token");
        }
        if (demoToken.equals(token)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private boolean protectedPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
