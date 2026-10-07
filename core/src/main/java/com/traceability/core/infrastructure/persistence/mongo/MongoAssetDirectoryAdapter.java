package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.core.application.port.out.AssetDirectoryPort;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/** Como {@link MongoFundDirectoryAdapter}: solo lee la génesis de cada stream; no cambia ningún evento. */
@Component
public class MongoAssetDirectoryAdapter implements AssetDirectoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoAssetDirectoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<String> findAssetIdsByOrganization(String organizationRef, int limit) {
        Query query = new Query(Criteria.where("aggregateType").is("PhysicalAsset").and("sequence").is(1L)
                .and("eventType").is("ASSET_REGISTERED")
                .and("payload.organizationRef").is(organizationRef))
                .with(Sort.by("recordedAt", "streamId")).limit(limit);
        query.fields().include("streamId");
        return mongoTemplate.find(query, TraceabilityEventDocument.class).stream()
                .map(TraceabilityEventDocument::getStreamId).toList();
    }
}
