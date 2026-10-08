package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.CampaignClearedFundsPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Solo lee el event store (índice {@code idx_event_type_campaign}); no cambia ningún evento (encargo 6, P3). */
@Component
public class MongoCampaignClearedFundsAdapter implements CampaignClearedFundsPort {

    private final MongoTemplate mongoTemplate;

    public MongoCampaignClearedFundsAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<ClearedFund> findClearedFundsByCampaign(String campaignRef, int limit) {
        Query query = new Query(Criteria.where("eventType").is("FUNDS_CLEARED").and("payload.campaignRef").is(campaignRef))
                .with(Sort.by("occurredAt", "streamId", "sequence")).limit(limit);
        query.fields().include("streamId").include("occurredAt").include("payload.clearedAmount").include("payload.donorRef");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .filter(e -> e.getOccurredAt() != null)
                .map(e -> new ClearedFund(e.getStreamId(), Instant.parse(e.getOccurredAt()),
                        ((Number) e.getPayload().get("clearedAmount")).longValue(), (String) e.getPayload().get("donorRef")))
                .toList();
    }
}
