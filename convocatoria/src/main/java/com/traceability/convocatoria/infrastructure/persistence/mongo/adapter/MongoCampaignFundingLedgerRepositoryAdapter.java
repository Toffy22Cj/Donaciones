package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.mapper.CampaignFundingLedgerMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adaptador Mongo de {@link CampaignFundingLedgerRepositoryPort}: escrituras condicionales atómicas de un solo
 * documento (ADR-037 §2.2). {@code status} de la convocatoria nunca forma parte del filtro (D1).
 */
@Component
public class MongoCampaignFundingLedgerRepositoryAdapter implements CampaignFundingLedgerRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoCampaignFundingLedgerRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(CampaignFundingLedger ledger) {
        mongoTemplate.insert(CampaignFundingLedgerMapper.toDocument(ledger));
    }

    @Override
    public Optional<CampaignFundingLedger> findByCampaignRef(String campaignRef) {
        return Optional.ofNullable(mongoTemplate.findById(campaignRef, CampaignFundingLedgerDocument.class))
                .map(CampaignFundingLedgerMapper::toDomain);
    }

    @Override
    public void deleteByCampaignRef(String campaignRef) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(campaignRef)), CampaignFundingLedgerDocument.class);
    }

    @Override
    public boolean incrementUnconditionally(String campaignRef, long amount) {
        Query query = Query.query(Criteria.where("_id").is(campaignRef));
        return mongoTemplate.updateFirst(query, new Update().inc("clearedAmount", amount),
                CampaignFundingLedgerDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean incrementWithinTarget(String campaignRef, long amount, long targetAmount) {
        Query query = Query.query(Criteria.where("_id").is(campaignRef)
                .and("clearedAmount").lte(targetAmount - amount));
        return mongoTemplate.updateFirst(query, new Update().inc("clearedAmount", amount),
                CampaignFundingLedgerDocument.class).getMatchedCount() == 1;
    }
}
