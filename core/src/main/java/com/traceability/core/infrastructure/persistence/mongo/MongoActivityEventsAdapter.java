package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.port.out.ActivityEventsPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Una consulta por página: {@code streamId $in}, {@code eventType $in} y el filtro del cursor, ordenada por
 * {@code recordedAt} descendente y {@code _id} ascendente, con un límite (plan D, §2.2; coste medido en §6.1, R-R6D-2).
 * El payload se tipa con {@link EventCanonicalMapper}, como {@code MongoEventStoreAdapter.loadStream}. Solo lectura.
 */
@Component
public class MongoActivityEventsAdapter implements ActivityEventsPort {

    private final MongoTemplate mongoTemplate;
    private final EventCanonicalMapper canonicalMapper;

    public MongoActivityEventsAdapter(MongoTemplate mongoTemplate, EventCanonicalMapper canonicalMapper) {
        this.mongoTemplate = mongoTemplate;
        this.canonicalMapper = canonicalMapper;
    }

    @Override
    public List<ActivityEvent> findPage(Collection<String> streamIds, Collection<String> eventTypes,
                                        String beforeRecordedAt, String afterEventId, int limit) {
        if (streamIds.isEmpty() || eventTypes.isEmpty()) {
            return List.of();
        }
        Criteria criteria = Criteria.where("streamId").in(streamIds).and("eventType").in(eventTypes);
        if (beforeRecordedAt != null) {
            criteria = afterEventId == null
                    ? criteria.and("recordedAt").lt(beforeRecordedAt)
                    : criteria.orOperator(Criteria.where("recordedAt").lt(beforeRecordedAt),
                            Criteria.where("recordedAt").is(beforeRecordedAt).and("_id").gt(afterEventId));
        }
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.asc("_id")))
                .limit(limit);
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(doc -> new ActivityEvent(doc.getEventId(), doc.getStreamId(), doc.getEventType(),
                        doc.getOccurredAt() != null ? Instant.parse(doc.getOccurredAt()) : null, doc.getRecordedAt(),
                        canonicalMapper.convertPayload(doc.getPayload(), doc.getEventType(), doc.getSchemaVersion())))
                .toList();
    }
}
