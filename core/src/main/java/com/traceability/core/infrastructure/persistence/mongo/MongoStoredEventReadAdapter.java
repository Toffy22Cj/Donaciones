package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.StoredEventReadPort;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Lee {@code event_store} en crudo (sin el mapeo de {@code TraceabilityEventDocument}); no cambia nada. */
@Component
public class MongoStoredEventReadAdapter implements StoredEventReadPort {

    private static final String COLLECTION = "event_store";

    private final MongoTemplate mongoTemplate;

    public MongoStoredEventReadAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<StoredEvent> findByCoverage(Map<String, SequenceRange> coverage) {
        List<StoredEvent> events = new ArrayList<>();
        for (Map.Entry<String, SequenceRange> e : new TreeMap<>(coverage).entrySet()) {
            Query query = new Query(Criteria.where("streamId").is(e.getKey()).and("sequence")
                    .gte(e.getValue().fromSequence()).lte(e.getValue().toSequence())).with(Sort.by("sequence"));
            mongoTemplate.find(query, Document.class, COLLECTION).forEach(d -> events.add(toEvent(d)));
        }
        return events;
    }

    @Override
    public Optional<StoredEvent> find(String streamId, long sequence) {
        Document d = mongoTemplate.findOne(new Query(Criteria.where("streamId").is(streamId).and("sequence").is(sequence)),
                Document.class, COLLECTION);
        return Optional.ofNullable(d).map(MongoStoredEventReadAdapter::toEvent);
    }

    @SuppressWarnings("unchecked")
    private static StoredEvent toEvent(Document d) {
        Object actorRef = d.get("actorRef");
        Object payload = d.get("payload");
        return new StoredEvent(String.valueOf(d.get("_id")), d.getString("streamId"), d.getString("aggregateType"),
                ((Number) d.get("sequence")).longValue(), d.getString("eventType"), d.getString("schemaVersion"),
                d.getString("occurredAt"), d.getString("recordedAt"), d.getString("origin"),
                payload instanceof Map<?, ?> m ? (Map<String, Object>) m : null, d.getString("previousHash"),
                d.getString("eventHash"), actorRef instanceof String s ? s : null);
    }
}
