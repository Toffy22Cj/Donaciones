package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ProcessedCommandDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.mapper.DonationIntentMapper;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Adaptador Mongo de {@link DonationIntentRepositoryPort} (ADR-037 §2.6; Enmienda §5.3).
 */
@Component
public class MongoDonationIntentRepositoryAdapter implements DonationIntentRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoDonationIntentRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(DonationIntent intent) {
        mongoTemplate.insert(DonationIntentMapper.toDocument(intent));
    }

    @Override
    public Optional<DonationIntent> findById(String intentId) {
        return Optional.ofNullable(mongoTemplate.findById(intentId, DonationIntentDocument.class))
                .map(DonationIntentMapper::toDomain);
    }

    @Override
    public boolean existsByCampaignRef(String campaignRef) {
        return mongoTemplate.exists(Query.query(Criteria.where("campaignRef").is(campaignRef)),
                DonationIntentDocument.class);
    }

    @Override
    public boolean confirmIfPending(String intentId, DonationIntent.Confirmation confirmation) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(intentId),
                Criteria.where("status").is(DonationIntentStatus.PENDING.name()),
                new Criteria().orOperator(
                        Criteria.where("expiresAt").exists(false),
                        Criteria.where("expiresAt").gt(confirmation.confirmedAt()))));
        Update update = new Update()
                .set("status", DonationIntentStatus.CONFIRMED.name())
                .set("confirmedBy", confirmation.confirmedBy())
                .set("confirmedAt", confirmation.confirmedAt())
                .set("confirmationPaymentMethod", confirmation.paymentMethod().name())
                .set("confirmationReference", confirmation.reference());
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean markFundingRejectedIfConfirmed(String intentId) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name()));
        Update update = new Update().set("status", DonationIntentStatus.FUNDING_REJECTED.name());
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    /**
     * {@code $lookup} sobre el registro de comandos procesados por la clave de sistema
     * {@code {commandType: APPLY_FUNDS, commandId: intentId}}; se descartan las intenciones con reclamo y, por un
     * segundo {@code $lookup} sobre el ledger (cuya configuración monetaria es inmutable), las de convocatorias
     * {@code CLOSE_ON_TARGET + CLOSE} mientras R4 no exista (P9).
     */
    @Override
    public List<DonationIntent> findConfirmedPendingApplication(int limit) {
        Document appliedKey = MongoProcessedCommandAdapter.systemKey(CommandType.APPLY_FUNDS, "$$intentId");
        AggregationOperation lookup = context -> new Document("$lookup", new Document()
                .append("from", ProcessedCommandDocument.COLLECTION)
                .append("let", new Document("intentId", "$_id"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$eq", List.of("$_id", appliedKey)))),
                        new Document("$limit", 1)))
                .append("as", "applied"));
        AggregationOperation ledger = context -> new Document("$lookup", new Document()
                .append("from", CampaignFundingLedgerDocument.COLLECTION)
                .append("localField", "campaignRef")
                .append("foreignField", "_id")
                .append("as", "ledger"));
        Criteria closeOnTargetClose = Criteria.where("ledger").elemMatch(new Criteria().andOperator(
                Criteria.where("targetPolicy").is(TargetPolicy.CLOSE_ON_TARGET.name()),
                Criteria.where("onTargetReached").is(OnTargetReached.CLOSE.name())));
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").is(DonationIntentStatus.CONFIRMED.name())),
                Aggregation.sort(Sort.Direction.ASC, "_id"),
                lookup,
                Aggregation.match(Criteria.where("applied").size(0)),
                ledger,
                Aggregation.match(new Criteria().norOperator(closeOnTargetClose)),
                Aggregation.limit(limit),
                Aggregation.project().andExclude("applied", "ledger"));
        return mongoTemplate.aggregate(aggregation, DonationIntentDocument.COLLECTION, DonationIntentDocument.class)
                .getMappedResults().stream().map(DonationIntentMapper::toDomain).toList();
    }
}
