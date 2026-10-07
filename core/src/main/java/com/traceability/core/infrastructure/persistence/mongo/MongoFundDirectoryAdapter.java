package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.FundDirectoryPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fondos de una organización por su génesis (secuencia 1, {@code FUND_REGISTERED} o {@code FUNDS_CLEARED} v2, que
 * llevan {@code organizationRef}). Solo lee el event store; no cambia ningún evento (plan P1.1, DD-31).
 */
@Component
public class MongoFundDirectoryAdapter implements FundDirectoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoFundDirectoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<String> findFundIdsByOrganization(String organizationRef, int limit) {
        Query query = new Query(Criteria.where("aggregateType").is("Fund").and("sequence").is(1L)
                .and("eventType").in("FUND_REGISTERED", "FUNDS_CLEARED")
                .and("payload.organizationRef").is(organizationRef))
                .with(Sort.by("recordedAt", "streamId")).limit(limit);
        query.fields().include("streamId");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(TraceabilityEventDocument::getStreamId).toList();
    }
}
