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

    @Override
    public boolean tryClaim(String commandId) {
        try {
            org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(commandId));
            org.springframework.data.mongodb.core.query.Update update = new org.springframework.data.mongodb.core.query.Update().setOnInsert("processedAt", Instant.now());
            org.springframework.data.mongodb.core.FindAndModifyOptions options = new org.springframework.data.mongodb.core.FindAndModifyOptions().upsert(true).returnNew(false);
            ProcessedCommandDocument prev = mongoTemplate.findAndModify(query, update, options, ProcessedCommandDocument.class);
            return prev == null;
        } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.dao.ConcurrencyFailureException e) {
            throw new com.traceability.core.application.exception.ConcurrencyConflictException("Write conflict claiming command " + commandId, e);
        }
    }
}
