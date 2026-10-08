package com.traceability.crypto.infrastructure.persistence.mongo;

import com.traceability.crypto.application.port.out.BatchReleaseAuditPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MongoBatchReleaseAuditAdapter implements BatchReleaseAuditPort {

    private final MongoTemplate mongoTemplate;

    public MongoBatchReleaseAuditAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void record(BatchRelease release) {
        BatchReleaseAuditDocument d = new BatchReleaseAuditDocument();
        d.id = UUID.randomUUID().toString();
        d.batchId = release.batchId();
        d.coverage = release.coverage();
        d.eventIds = release.eventIds();
        d.operator = release.operator();
        d.reason = release.reason();
        d.releasedAt = release.releasedAt();
        mongoTemplate.insert(d);
    }
}
