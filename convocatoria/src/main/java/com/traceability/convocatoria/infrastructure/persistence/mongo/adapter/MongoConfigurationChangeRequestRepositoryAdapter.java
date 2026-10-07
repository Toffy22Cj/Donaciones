package com.traceability.convocatoria.infrastructure.persistence.mongo.adapter;

import com.traceability.convocatoria.application.port.out.ConfigurationChangeRequestRepositoryPort;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeRequestAlreadyPendingException;
import com.traceability.convocatoria.domain.model.ConfigurationChangeRequest;
import com.traceability.convocatoria.domain.model.ConfigurationChangeRequestStatus;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConfigurationChangeRequestDocument;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import jakarta.annotation.PostConstruct;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Adaptador Mongo de {@link ConfigurationChangeRequestRepositoryPort} (Enmienda 4 de ADR-037, D2). */
@Component
public class MongoConfigurationChangeRequestRepositoryAdapter implements ConfigurationChangeRequestRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoConfigurationChangeRequestRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * El índice único parcial se crea al arrancar y no depende de {@code auto-index-creation} ni del primer uso, que
     * podría caer dentro de una transacción: sin él, dos solicitudes pendientes serían posibles.
     */
    @PostConstruct
    void ensureIndexes() {
        mongoTemplate.indexOps(ConfigurationChangeRequestDocument.class).ensureIndex(new Index()
                .on("campaignRef", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("status").is(ConfigurationChangeRequestStatus.PENDING.name())))
                .named(ConfigurationChangeRequestDocument.PENDING_INDEX));
    }

    @Override
    public void insert(ConfigurationChangeRequest request) {
        try {
            mongoTemplate.insert(toDocument(request));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(ConfigurationChangeRequestDocument.PENDING_INDEX)) {
                throw new ConfigurationChangeRequestAlreadyPendingException(
                        "Campaign " + request.campaignRef() + " already has a pending configuration change request");
            }
            throw e;
        }
    }

    @Override
    public Optional<ConfigurationChangeRequest> findById(String requestId) {
        return Optional.ofNullable(mongoTemplate.findById(requestId, ConfigurationChangeRequestDocument.class))
                .map(MongoConfigurationChangeRequestRepositoryAdapter::toDomain);
    }

    @Override
    public List<ConfigurationChangeRequest> findByCampaignRef(String campaignRef, int limit) {
        Query query = Query.query(Criteria.where("campaignRef").is(campaignRef))
                .with(Sort.by(Sort.Direction.DESC, "requestedAt", "_id")).limit(limit);
        return mongoTemplate.find(query, ConfigurationChangeRequestDocument.class).stream()
                .map(MongoConfigurationChangeRequestRepositoryAdapter::toDomain).toList();
    }

    @Override
    public boolean markApprovedIfPending(String requestId, String decidedBy, Instant decidedAt, long resultingVersion) {
        return transition(requestId, new Update().set("status", ConfigurationChangeRequestStatus.APPROVED.name())
                .set("decidedBy", decidedBy).set("decidedAt", decidedAt)
                .set("resultingConfigurationVersion", resultingVersion));
    }

    @Override
    public boolean markRejectedIfPending(String requestId, String decidedBy, Instant decidedAt) {
        return transition(requestId, new Update().set("status", ConfigurationChangeRequestStatus.REJECTED.name())
                .set("decidedBy", decidedBy).set("decidedAt", decidedAt));
    }

    private boolean transition(String requestId, Update update) {
        Query query = Query.query(Criteria.where("_id").is(requestId)
                .and("status").is(ConfigurationChangeRequestStatus.PENDING.name()));
        return mongoTemplate.updateFirst(query, update, ConfigurationChangeRequestDocument.class).getModifiedCount() == 1;
    }

    private static ConfigurationChangeRequestDocument toDocument(ConfigurationChangeRequest r) {
        ConfigurationChangeRequestDocument d = new ConfigurationChangeRequestDocument();
        d.requestId = r.requestId();
        d.campaignRef = r.campaignRef();
        d.organizationRef = r.organizationRef();
        d.baseConfigurationVersion = r.baseConfigurationVersion();
        ConvocatoriaConfiguration c = r.proposedConfiguration();
        d.acceptedDonationTypes = c.acceptedDonationTypes().stream().map(Enum::name).collect(Collectors.toSet());
        d.acceptedPaymentMethods = c.acceptedPaymentMethods().stream().map(Enum::name).collect(Collectors.toSet());
        d.currency = c.currency();
        d.targetAmount = c.targetAmount();
        d.targetPolicy = c.targetPolicy() == null ? null : c.targetPolicy().name();
        d.onTargetReached = c.onTargetReached() == null ? null : c.onTargetReached().name();
        d.requestedBy = r.requestedBy();
        d.requestedAt = r.requestedAt();
        d.status = r.status().name();
        d.decidedBy = r.decidedBy();
        d.decidedAt = r.decidedAt();
        d.resultingConfigurationVersion = r.resultingConfigurationVersion();
        return d;
    }

    private static ConfigurationChangeRequest toDomain(ConfigurationChangeRequestDocument d) {
        ConvocatoriaConfiguration c = new ConvocatoriaConfiguration(
                d.acceptedDonationTypes.stream().map(DonationType::valueOf).collect(Collectors.toSet()),
                d.acceptedPaymentMethods == null ? Set.of()
                        : d.acceptedPaymentMethods.stream().map(PaymentMethod::valueOf).collect(Collectors.toSet()),
                d.currency, d.targetAmount,
                d.targetPolicy == null ? null : TargetPolicy.valueOf(d.targetPolicy),
                d.onTargetReached == null ? null : OnTargetReached.valueOf(d.onTargetReached));
        return new ConfigurationChangeRequest(d.requestId, d.campaignRef, d.organizationRef, d.baseConfigurationVersion,
                c, d.requestedBy, d.requestedAt, ConfigurationChangeRequestStatus.valueOf(d.status), d.decidedBy,
                d.decidedAt, d.resultingConfigurationVersion);
    }
}
