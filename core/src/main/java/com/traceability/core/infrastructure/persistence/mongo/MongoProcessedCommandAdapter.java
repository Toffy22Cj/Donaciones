package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MongoProcessedCommandAdapter implements ProcessedCommandRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoProcessedCommandAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void save(String commandId) {
        ProcessedCommandDocument doc = new ProcessedCommandDocument(commandId, Instant.now());
        mongoTemplate.insert(doc);
    }

    @Override
    public boolean exists(String commandId) {
        return mongoTemplate.findById(commandId, ProcessedCommandDocument.class) != null;
    }
}
