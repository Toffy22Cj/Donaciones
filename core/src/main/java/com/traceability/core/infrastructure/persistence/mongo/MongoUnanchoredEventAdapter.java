package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.core.domain.exception.OrphanClaimConcurrencyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MongoUnanchoredEventAdapter implements UnanchoredEventRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoUnanchoredEventAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Map<String, SequenceRange> claimOrphansAndAssignBatch(String batchId, int maxStreams, int maxEventsPerBatch) {
        // Paso 1: identificar qué streams DISTINTOS tienen huérfanos
        Query distinctQuery = new Query(Criteria.where("merkleBatchId").isNull());
        List<String> distinctStreams = mongoTemplate.findDistinct(distinctQuery, "streamId", TraceabilityEventDocument.class, String.class);

        if (distinctStreams.isEmpty()) {
            return Map.of();
        }

        // Orden estable (streamId ASC)
        List<String> sortedStreams = new ArrayList<>(distinctStreams);
        Collections.sort(sortedStreams);

        // Limitar a maxStreams
        List<String> eligibleStreams = sortedStreams.stream().limit(maxStreams).toList();

        // Paso 2: repartir el presupuesto
        int streamsCount = eligibleStreams.size();
        int baseBudget = maxEventsPerBatch / streamsCount;
        int remainder = maxEventsPerBatch % streamsCount;

        List<TraceabilityEventDocument> selected = new ArrayList<>();

        // Paso 3: UNA query por stream elegible
        for (int i = 0; i < streamsCount; i++) {
            String streamId = eligibleStreams.get(i);
            int budget = baseBudget + (i == 0 ? remainder : 0);

            if (budget == 0) {
                continue;
            }

            Query streamQuery = new Query(Criteria.where("merkleBatchId").isNull().and("streamId").is(streamId))
                    .with(Sort.by(Sort.Direction.ASC, "sequence"))
                    .limit(budget);

            selected.addAll(mongoTemplate.find(streamQuery, TraceabilityEventDocument.class));
        }

        if (selected.isEmpty()) {
            return Map.of();
        }

        List<String> eventIds = selected.stream().map(TraceabilityEventDocument::getEventId).toList();

        // Update the specifically selected events
        Query updateQuery = new Query(Criteria.where("_id").in(eventIds).and("merkleBatchId").isNull());
        Update update = new Update().set("merkleBatchId", batchId);
        
        long modifiedCount = mongoTemplate.updateMulti(updateQuery, update, TraceabilityEventDocument.class).getModifiedCount();
        
        if (modifiedCount != selected.size()) {
            throw new OrphanClaimConcurrencyException(batchId, selected.size(), modifiedCount);
        }

        // Build the coverage map
        Map<String, SequenceRange> coverage = new LinkedHashMap<>();
        for (TraceabilityEventDocument doc : selected) {
            coverage.compute(doc.getStreamId(), (k, currentRange) -> {
                if (currentRange == null) {
                    return new SequenceRange(doc.getSequence(), doc.getSequence());
                }
                long min = Math.min(currentRange.fromSequence(), doc.getSequence());
                long max = Math.max(currentRange.toSequence(), doc.getSequence());
                return new SequenceRange(min, max);
            });
        }
        return coverage;
    }

    @Override
    public List<String> getEventHashesByCoverage(Map<String, SequenceRange> coverage) {
        if (coverage.isEmpty()) {
            return List.of();
        }
        
        Criteria[] orCriteria = coverage.entrySet().stream().map(entry -> 
            Criteria.where("streamId").is(entry.getKey())
                    .and("sequence").gte(entry.getValue().fromSequence()).lte(entry.getValue().toSequence())
        ).toArray(Criteria[]::new);

        // Explicit canonical sort as required by the plan
        Query query = new Query(new Criteria().orOperator(orCriteria))
                .with(Sort.by(Sort.Direction.ASC, "streamId", "sequence"));
        
        query.fields().include("eventHash");

        List<TraceabilityEventDocument> docs = mongoTemplate.find(query, TraceabilityEventDocument.class);
        return docs.stream().map(TraceabilityEventDocument::getEventHash).toList();
    }
}
