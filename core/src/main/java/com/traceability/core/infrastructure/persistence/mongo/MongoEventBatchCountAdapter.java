package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.EventBatchCountPort;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@code $match {streamId $in}} y {@code $group} por {@code merkleBatchId} (plan D, §2.1; medido en §6.1). Solo lee el
 * event store; no cambia ningún evento.
 */
@Component
public class MongoEventBatchCountAdapter implements EventBatchCountPort {

    private final MongoTemplate mongoTemplate;

    public MongoEventBatchCountAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<BatchEventCount> countByBatch(Collection<String> streamIds) {
        if (streamIds.isEmpty()) {
            return List.of();
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("streamId").in(streamIds)),
                Aggregation.group("merkleBatchId").count().as("events"));
        return mongoTemplate.aggregate(aggregation, TraceabilityEventDocument.class, Document.class).getMappedResults()
                .stream()
                .map(d -> new BatchEventCount(d.getString("_id"), ((Number) d.get("events")).longValue()))
                .toList();
    }
}
