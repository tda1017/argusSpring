package com.argus.review.aiops.memory;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 服务级长期记忆。
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "service_memory")
public class ServiceMemoryRecord {

    @Id
    private String id;

    @Indexed
    private String serviceName;

    private String alertName;
    private String lastRootCause;
    private String lastFixSuggestion;
    private int occurrences;
    private int verifiedSuccesses;
    private long updatedAt;
}
