package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.EventBatchMembershipPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Solo lee el event store (índice único {@code streamId, sequence}); no cambia ningún evento (encargo 6, P4). */
@Component
public class MongoEventBatchMembershipAdapter implements EventBatchMembershipPort {

    private final MongoTemplate mongoTemplate;

    public MongoEventBatchMembershipAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<EventMembership> findMembership(Collection<String> streamIds, int limit) {
        if (streamIds.isEmpty()) {
            return List.of();
        }
        Query query = new Query(Criteria.where("streamId").in(streamIds))
                .with(Sort.by("streamId", "sequence")).limit(limit);
        query.fields().include("streamId").include("sequence").include("merkleBatchId");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(e -> new EventMembership(e.getStreamId(), e.getSequence(), e.getMerkleBatchId()))
                .toList();
    }
}
