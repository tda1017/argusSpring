package com.argus.review.aiops.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Memory 查询接口。
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    @GetMapping
    public Flux<ServiceMemoryRecord> list() {
        return memoryService.recent();
    }
}
