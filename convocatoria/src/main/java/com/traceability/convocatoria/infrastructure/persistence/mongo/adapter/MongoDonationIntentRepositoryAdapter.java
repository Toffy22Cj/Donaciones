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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
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
    public boolean markFundingRejectedIfConfirmed(String intentId, DonationIntent.FundingRejection rejection) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name()));
        Update update = new Update().set("status", DonationIntentStatus.FUNDING_REJECTED.name())
                .set("fundingRejectedAt", rejection.rejectedAt())
                .set("fundingRejectionReason", rejection.reason());
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean markFundsApplied(String intentId, Instant appliedAt) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").exists(false));
        return mongoTemplate.updateFirst(query, new Update().set("fundsAppliedAt", appliedAt),
                DonationIntentDocument.class).getMatchedCount() == 1;
    }

    @Override
    public Optional<DonationIntent.ApplicationTracking> recordApplicationFailure(String intentId, String errorClass,
                                                                                 Instant attemptedAt, boolean quarantine) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").exists(false));
        Update update = new Update()
                .inc("applicationAttempts", 1)
                .min("firstApplicationAttemptAt", attemptedAt)
                .set("lastApplicationAttemptAt", attemptedAt)
                .set("lastApplicationError", errorClass);
        if (quarantine) {
            update.set("applicationQuarantined", true);
        }
        DonationIntentDocument updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), DonationIntentDocument.class);
        return Optional.ofNullable(updated).map(d -> DonationIntentMapper.toDomain(d).getApplicationTracking());
    }

    @Override
    public boolean quarantineApplication(String intentId) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").exists(false)
                .and("applicationQuarantined").ne(true));
        return mongoTemplate.updateFirst(query, new Update().set("applicationQuarantined", true),
                DonationIntentDocument.class).getModifiedCount() == 1;
    }

    @Override
    public boolean releaseApplicationQuarantine(String intentId) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").exists(false)
                .and("applicationQuarantined").is(true));
        Update update = new Update().set("applicationQuarantined", false).set("applicationAttempts", 0)
                .unset("firstApplicationAttemptAt").unset("lastApplicationAttemptAt").unset("lastApplicationError");
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    /**
     * Consulta de recuperables (ADR-045 §2.4, §2.5). Filtro sobre el índice parcial {@code ix_pending_application}
     * ({@code status = CONFIRMED}, {@code fundsAppliedAt = null}), orden de equidad, {@code $lookup} defensivo del
     * reclamo {@code {commandType: APPLY_FUNDS, commandId: intentId}} y exclusión de {@code CLOSE_ON_TARGET + CLOSE}
     * (P9) por {@code $lookup} sobre el ledger, cuya configuración monetaria es inmutable.
     */
    @Override
    public List<DonationIntent> findConfirmedPendingApplication(int limit) {
        Aggregation aggregation = Aggregation.newAggregation(pendingPipeline(
                Aggregation.sort(Sort.by(Sort.Order.asc("applicationAttempts"),
                        Sort.Order.asc("lastApplicationAttemptAt"), Sort.Order.asc("_id"))),
                false, Aggregation.limit(limit)));
        return mongoTemplate.aggregate(aggregation, DonationIntentDocument.COLLECTION, DonationIntentDocument.class)
                .getMappedResults().stream().map(DonationIntentMapper::toDomain).toList();
    }

    @Override
    public long countApplicationQuarantined() {
        return mongoTemplate.count(Query.query(Criteria.where("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").is(null)
                .and("applicationQuarantined").is(true)), DonationIntentDocument.class);
    }

    @Override
    public long countPendingExcludedByCloseOnTargetClose() {
        Aggregation aggregation = Aggregation.newAggregation(pendingPipeline(null, true, Aggregation.count().as("n")));
        Document result = mongoTemplate.aggregate(aggregation, DonationIntentDocument.COLLECTION, Document.class)
                .getUniqueMappedResult();
        return result == null ? 0 : ((Number) result.get("n")).longValue();
    }

    @Override
    public Optional<Instant> oldestPendingApplicationConfirmedAt() {
        Aggregation aggregation = Aggregation.newAggregation(pendingPipeline(
                Aggregation.sort(Sort.Direction.ASC, "confirmedAt"), false, Aggregation.limit(1)));
        return mongoTemplate.aggregate(aggregation, DonationIntentDocument.COLLECTION, DonationIntentDocument.class)
                .getMappedResults().stream().findFirst().map(d -> d.confirmedAt);
    }

    /**
     * Recuperables sin reclamo; con {@code onlyCloseOnTargetClose} devuelve en cambio las excluidas por P9.
     */
    private List<AggregationOperation> pendingPipeline(AggregationOperation sort, boolean onlyCloseOnTargetClose,
                                                       AggregationOperation tail) {
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
        List<AggregationOperation> ops = new ArrayList<>();
        ops.add(Aggregation.match(Criteria.where("status").is(DonationIntentStatus.CONFIRMED.name())
                .and("fundsAppliedAt").is(null)
                .and("applicationQuarantined").ne(true)));
        if (sort != null) {
            ops.add(sort);
        }
        ops.add(lookup);
        ops.add(Aggregation.match(Criteria.where("applied").size(0)));
        ops.add(ledger);
        ops.add(Aggregation.match(onlyCloseOnTargetClose ? closeOnTargetClose : new Criteria().norOperator(closeOnTargetClose)));
        ops.add(tail);
        if (!onlyCloseOnTargetClose) {
            ops.add(Aggregation.project().andExclude("applied", "ledger"));
        }
        return ops;
    }

    @Override
    public Optional<DonationIntent> findByPaymentSessionId(String paymentSessionId) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("paymentSessionId").is(paymentSessionId)),
                DonationIntentDocument.class)).map(DonationIntentMapper::toDomain);
    }

    @Override
    public boolean confirmGatewayIfPending(String intentId, DonationIntent.Confirmation confirmation,
                                           String providerEventId) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.PENDING.name()));
        Update update = new Update()
                .set("status", DonationIntentStatus.CONFIRMED.name())
                .set("confirmedBy", confirmation.confirmedBy())
                .set("confirmedAt", confirmation.confirmedAt())
                .set("confirmationPaymentMethod", confirmation.paymentMethod().name())
                .set("confirmationReference", confirmation.reference())
                .set("providerEventId", providerEventId);
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    @Override
    public boolean failIfPending(String intentId, String providerEventId, Instant failedAt) {
        Query query = Query.query(Criteria.where("_id").is(intentId)
                .and("status").is(DonationIntentStatus.PENDING.name()));
        Update update = new Update().set("status", DonationIntentStatus.FAILED.name())
                .set("providerEventId", providerEventId).set("failedAt", failedAt);
        return mongoTemplate.updateFirst(query, update, DonationIntentDocument.class).getMatchedCount() == 1;
    }

    @Override
    public List<DonationIntent> findByDonorRef(String donorRef, int limit) {
        Query query = Query.query(Criteria.where("donorRef").is(donorRef))
                .with(org.springframework.data.domain.Sort.by("_id")).limit(limit);
        return mongoTemplate.find(query, DonationIntentDocument.class).stream().map(DonationIntentMapper::toDomain)
                .toList();
    }
}
