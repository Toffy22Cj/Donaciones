package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.CampaignResponsibleStatePort;
import com.traceability.convocatoria.domain.model.CampaignResponsibleState;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignResponsibleStateDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adaptador Mongo del contador {@code CampaignResponsibleState} (ADR-037 §2.5).
 */
@Component
public class MongoCampaignResponsibleStateAdapter implements CampaignResponsibleStatePort {

    private final MongoTemplate mongoTemplate;

    public MongoCampaignResponsibleStateAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void increment(String campaignRef) {
        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(campaignRef)),
                new Update().inc("activeResponsibleCount", 1), CampaignResponsibleStateDocument.class);
    }

    @Override
    public boolean decrementIfMoreThanOne(String campaignRef) {
        Query query = Query.query(Criteria.where("_id").is(campaignRef).and("activeResponsibleCount").gt(1));
        return mongoTemplate.updateFirst(query, new Update().inc("activeResponsibleCount", -1),
                CampaignResponsibleStateDocument.class).getMatchedCount() == 1;
    }

    @Override
    public Optional<CampaignResponsibleState> find(String campaignRef) {
        return Optional.ofNullable(mongoTemplate.findById(campaignRef, CampaignResponsibleStateDocument.class))
                .map(d -> new CampaignResponsibleState(d.campaignRef, d.activeResponsibleCount));
    }
}
