package com.argus.review.aiops.persistence;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

/**
 * 诊断记录仓库。
 */
public interface DiagnosisRecordRepository extends ReactiveMongoRepository<DiagnosisRecord, String> {
}
