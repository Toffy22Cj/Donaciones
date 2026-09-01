package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.application.saga.OutboxStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MongoOutboxPort implements OutboxPort {

    private final MongoTemplate mongoTemplate;

    public MongoOutboxPort(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void save(OutboxMessage message) {
        OutboxMessageDocument doc = toDocument(message);
        mongoTemplate.insert(doc);
    }

    @Override
    public List<OutboxMessage> fetchPendingMessages(Instant now) {
        Query query = new Query(
                Criteria.where("status").is(OutboxStatus.PENDING.name())
                        .and("nextRetryAt").lte(now)
        );
        return mongoTemplate.find(query, OutboxMessageDocument.class).stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
    }

    @Override
    public void update(OutboxMessage message) {
        OutboxMessageDocument doc = toDocument(message);
        mongoTemplate.save(doc); // save performs an upsert if it exists
    }

    private OutboxMessageDocument toDocument(OutboxMessage msg) {
        return OutboxMessageDocument.builder()
                .messageId(msg.messageId())
                .sagaType(msg.sagaType())
                .sourceAggregateId(msg.sourceAggregateId())
                .correlationId(msg.correlationId())
                .payload(msg.payload())
                .status(msg.status().name())
                .retryCount(msg.retryCount())
                .createdAt(msg.createdAt())
                .nextRetryAt(msg.nextRetryAt())
                .build();
    }

    private OutboxMessage toMessage(OutboxMessageDocument doc) {
        return new OutboxMessage(
                doc.getMessageId(),
                doc.getSagaType(),
                doc.getSourceAggregateId(),
                doc.getCorrelationId(),
                doc.getPayload(),
                OutboxStatus.valueOf(doc.getStatus()),
                doc.getRetryCount(),
                doc.getCreatedAt(),
                doc.getNextRetryAt()
        );
    }
}
