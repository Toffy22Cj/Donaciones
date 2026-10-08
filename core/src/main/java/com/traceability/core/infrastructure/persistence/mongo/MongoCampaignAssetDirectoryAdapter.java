package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.CampaignAssetDirectoryPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/** Solo lee el event store; no cambia ningún evento (plan B5, DD-33). */
@Component
public class MongoCampaignAssetDirectoryAdapter implements CampaignAssetDirectoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoCampaignAssetDirectoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<String> findAssetIdsByCampaign(String campaignRef) {
        Query query = new Query(Criteria.where("eventType").is("ASSET_REGISTERED").and("sequence").is(1L)
                .and("payload.campaignRef").is(campaignRef));
        query.fields().include("streamId");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(TraceabilityEventDocument::getStreamId).distinct().toList();
    }
}
