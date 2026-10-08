package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.application.saga.OutboxStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    @Override
    public Optional<OutboxMessage> findById(String messageId) {
        return Optional.ofNullable(mongoTemplate.findById(messageId, OutboxMessageDocument.class)).map(this::toMessage);
    }

    @Override
    public Optional<OutboxMessage> findBySagaTypeAndCorrelationId(String sagaType, String correlationId) {
        Query query = new Query(Criteria.where("sagaType").is(sagaType).and("correlationId").is(correlationId));
        return Optional.ofNullable(mongoTemplate.findOne(query, OutboxMessageDocument.class)).map(this::toMessage);
    }

    @Override
    public List<OutboxMessage> findQuarantined(String sagaType, int limit) {
        Criteria criteria = Criteria.where("status").is(OutboxStatus.QUARANTINED.name());
        if (sagaType != null && !sagaType.isBlank()) {
            criteria = criteria.and("sagaType").is(sagaType);
        }
        Query query = new Query(criteria).with(Sort.by("createdAt")).limit(Math.max(1, limit));
        return mongoTemplate.find(query, OutboxMessageDocument.class).stream().map(this::toMessage).toList();
    }

    @Override
    public long countQuarantined() {
        return mongoTemplate.count(new Query(Criteria.where("status").is(OutboxStatus.QUARANTINED.name())),
                OutboxMessageDocument.class);
    }

    @Override
    public boolean updateIfStatus(OutboxMessage message, OutboxStatus expected) {
        OutboxMessageDocument doc = toDocument(message);
        Query query = new Query(Criteria.where("_id").is(message.messageId()).and("status").is(expected.name()));
        Update update = new Update()
                .set("status", doc.getStatus())
                .set("retryCount", doc.getRetryCount())
                .set("nextRetryAt", doc.getNextRetryAt())
                .set("resolutionStartedAt", doc.getResolutionStartedAt())
                .set("lastFailureReason", doc.getLastFailureReason())
                .set("manualResolvedBy", doc.getManualResolvedBy())
                .set("manualNote", doc.getManualNote())
                .set("manualResolvedAt", doc.getManualResolvedAt());
        return mongoTemplate.updateFirst(query, update, OutboxMessageDocument.class).getModifiedCount() == 1;
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
                .resolutionStartedAt(msg.resolutionStartedAt())
                .lastFailureReason(msg.lastFailureReason())
                .manualResolvedBy(msg.manualResolvedBy())
                .manualNote(msg.manualNote())
                .manualResolvedAt(msg.manualResolvedAt())
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
                doc.getNextRetryAt(),
                doc.getResolutionStartedAt(),
                doc.getLastFailureReason(),
                doc.getManualResolvedBy(),
                doc.getManualNote(),
                doc.getManualResolvedAt()
        );
    }
}
