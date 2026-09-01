package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.application.exception.SequenceGapException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class MongoEventStoreAdapter implements EventStorePort {

    private final MongoTemplate mongoTemplate;
    private final HashPort hashPort;
    private final EventCanonicalMapper canonicalMapper;

    public MongoEventStoreAdapter(MongoTemplate mongoTemplate, HashPort hashPort, EventCanonicalMapper canonicalMapper) {
        this.mongoTemplate = mongoTemplate;
        this.hashPort = hashPort;
        this.canonicalMapper = canonicalMapper;
    }

    @Override
    public void append(String streamId, String aggregateType, long expectedVersion, DomainEvent event, String actorRef) {
        String previousHash;

        if (expectedVersion == 0) {
            previousHash = DomainEvent.GENESIS_HASH;
        } else {
            Query query = new Query(Criteria.where("streamId").is(streamId).and("sequence").is(expectedVersion));
            TraceabilityEventDocument prevDoc = mongoTemplate.findOne(query, TraceabilityEventDocument.class);
            if (prevDoc == null) {
                throw new SequenceGapException("Expected event with sequence " + expectedVersion + " not found in stream " + streamId);
            }
            previousHash = prevDoc.getEventHash();
        }

        long newSequence = expectedVersion + 1;
        String eventId = UUID.randomUUID().toString();
        Instant recordedAt = Instant.now();
        String origin = "TRACEABILITY_CORE";
        String schemaVersion = "1.0";

        // Assemble generic map
        Map<String, Object> eventData = canonicalMapper.toCanonicalMap(
                eventId,
                streamId,
                aggregateType,
                newSequence,
                event.eventType().name(),
                schemaVersion,
                event.occurredAt(),
                recordedAt,
                actorRef,
                origin,
                event.payload()
        );

        // Generate hash
        String eventHash = hashPort.canonicalizeAndHash(eventData, previousHash);

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) eventData.get("payload");

        // Build document
        TraceabilityEventDocument newDoc = TraceabilityEventDocument.builder()
                .eventId(eventId)
                .streamId(streamId)
                .aggregateType(aggregateType)
                .sequence(newSequence)
                .eventType(event.eventType().name())
                .schemaVersion(schemaVersion)
                .occurredAt(event.occurredAt() != null ? event.occurredAt().toString() : null)
                .recordedAt(recordedAt.toString())
                .actorRef(actorRef)
                .origin(origin)
                .payload(payloadMap)
                .previousHash(previousHash)
                .eventHash(eventHash)
                .build();

        try {
            mongoTemplate.insert(newDoc);
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflictException("Concurrency conflict appending to stream " + streamId + " at sequence " + newSequence, e);
        }
    }

    @Override
    public List<DomainEvent> loadStream(String streamId) {
        Query query = new Query(Criteria.where("streamId").is(streamId))
                .with(Sort.by(Sort.Direction.ASC, "sequence"));
        
        List<TraceabilityEventDocument> docs = mongoTemplate.find(query, TraceabilityEventDocument.class);

        return docs.stream().map(doc -> {
            DomainEventPayload typedPayload = canonicalMapper.convertPayload(doc.getPayload(), doc.getEventType());
            return new DomainEvent(
                    () -> doc.getEventType(), 
                    typedPayload,
                    doc.getOccurredAt() != null ? Instant.parse(doc.getOccurredAt()) : null
            );
        }).collect(Collectors.toList());
    }
}
