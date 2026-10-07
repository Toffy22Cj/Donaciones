package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.*;
import com.traceability.core.domain.physicalasset.payloads.*;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.*;
import com.traceability.core.infrastructure.projection.mongo.repositories.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.bson.types.Decimal128;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

/**
 * Proyección {@code DonationProjection} (+ {@code asset_index} y {@code asset_history}). B-PROJ: la génesis de un
 * stream es la secuencia 1 ("nada procesado" = 0), se tratan los payloads v1 y v2, y los activos del Camino B se
 * ignoran de forma explícita ({@link AssetProjectionRouting}). Primero en el orden: los demás manejadores dependen del
 * {@code asset_index} que escribe.
 */
@Service
@Order(0)
public class DonationProjectionHandler implements ProjectionEventHandler {
    private static final Logger log = LoggerFactory.getLogger(DonationProjectionHandler.class);

    private static final Set<Class<? extends DomainEventPayload>> HANDLED = Set.of(
            FundRegisteredPayload.class, FundRegisteredV2Payload.class,
            FundsClearedPayload.class, FundsClearedV2Payload.class,
            AllocationRequestedPayload.class, AllocationConfirmedPayload.class, AllocationReversedPayload.class,
            FundsRefundedPayload.class,
            AssetRegisteredPayload.class, AssetRegisteredV2Payload.class, AssetRegisteredV3Payload.class,
            AssetDispatchedPayload.class, AssetReceivedPayload.class, AssetCustodyTransferredPayload.class,
            AssetSplitPayload.class, AssetSplitV2Payload.class, AssetSplitV3Payload.class, AssetSplitCompensatedPayload.class,
            AssetDepletedPayload.class, AssetDeliveredPayload.class);

    @Override
    public String getHandlerName() {
        return "DonationProjectionHandler";
    }

    @Override
    public Set<Class<? extends DomainEventPayload>> handledPayloads() {
        return HANDLED;
    }

    @Override
    public Set<Class<? extends DomainEventPayload>> ignoredPayloads() {
        return Set.of();
    }
    private final MongoTemplate mongoTemplate;
    private final EventCanonicalMapper canonicalMapper;
    private final DonationProjectionRepository projectionRepository;
    private final AssetHistoryProjectionRepository historyRepository;
    private final AssetProjectionRouting routing;
    private final UndeclaredPayloadMonitor undeclaredPayloads;

    public DonationProjectionHandler(MongoTemplate mongoTemplate,
                                     EventCanonicalMapper canonicalMapper,
                                     DonationProjectionRepository projectionRepository,
                                     AssetHistoryProjectionRepository historyRepository,
                                     AssetProjectionRouting routing,
                                     UndeclaredPayloadMonitor undeclaredPayloads) {
        this.mongoTemplate = mongoTemplate;
        this.canonicalMapper = canonicalMapper;
        this.projectionRepository = projectionRepository;
        this.historyRepository = historyRepository;
        this.routing = routing;
        this.undeclaredPayloads = undeclaredPayloads;
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
        processEvent(eventDoc);
    }

    private void processEvent(TraceabilityEventDocument eventDoc) {
        String streamId = eventDoc.getStreamId();
        long incomingSequence = eventDoc.getSequence();
        String aggregateType = eventDoc.getAggregateType();

        if ("Fund".equals(aggregateType)) {
            processFundEvent(eventDoc, streamId, incomingSequence);
        } else if ("PhysicalAsset".equals(aggregateType)) {
            processPhysicalAssetEvent(eventDoc, streamId, incomingSequence);
        }
    }

    private void processFundEvent(TraceabilityEventDocument eventDoc, String fundId, long incomingSequence) {
        DonationProjectionDocument projection = projectionRepository.findById(fundId).orElse(null);
        
        boolean isNew = projection == null;
        // Génesis = secuencia 1 (D-SEQ): "nada procesado" es 0.
        long lastProcessed = isNew ? 0 : projection.getAuditMetadata().getFundLastProcessedSequence();
        
        if (incomingSequence <= lastProcessed) {
            return; // Duplicate, ignore
        }
        if (incomingSequence > lastProcessed + 1) {
            throw new SequenceGapException("Gap in Fund stream " + fundId);
        }

        if (projection == null) {
            projection = new DonationProjectionDocument();
            projection.setProjectionId(fundId);
            projection.setStatus("ACTIVE");
        } else if ("PAUSED".equals(projection.getStatus())) {
            throw new ProjectionPausedException("Projection is PAUSED");
        }

        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType(), eventDoc.getSchemaVersion());
        undeclaredPayloads.checkDeclared(this, payload);

        // Update Snapshot and Allocations using MongoTemplate update for efficiency if exists, 
        // but for Fund it's easier to modify the object and save it since it's a single document
        // and we need to check idempotence locally first. Actually, ADR-010 requires positional updates,
        // but since we loaded it, we can just save it. Wait, the prompt says:
        // "Actualiza el documento DonationProjection correspondiente con operaciones posicionales ($set, $push) — NO reescribas el documento completo"
        
        Update update = new Update();
        update.set("auditMetadata.fundLastProcessedSequence", incomingSequence);

        if (payload instanceof FundRegisteredPayload p) {
            update.set("financialSnapshot.originalAmount", p.pledgedAmount() != null ? p.pledgedAmount() : 0);
            if (p.currency() != null) update.set("currency", p.currency());
            if (p.campaignRef() != null) update.set("campaignRef", p.campaignRef());
        } else if (payload instanceof FundRegisteredV2Payload p) {
            update.set("financialSnapshot.originalAmount", p.pledgedAmount() != null ? p.pledgedAmount() : 0);
            if (p.currency() != null) update.set("currency", p.currency());
            if (p.campaignRef() != null) update.set("campaignRef", p.campaignRef());
        } else if (payload instanceof FundsClearedPayload p) {
            update.inc("financialSnapshot.clearedAmount", p.clearedAmount());
            if (isNew) { // génesis directa (clearFundsGenesis): lo liquidado es el importe original
                update.set("financialSnapshot.originalAmount", p.clearedAmount());
            }
            if (p.currency() != null) update.set("currency", p.currency());
            if (p.campaignRef() != null) update.set("campaignRef", p.campaignRef());
        } else if (payload instanceof FundsClearedV2Payload p) {
            update.inc("financialSnapshot.clearedAmount", p.clearedAmount());
            if (isNew) { // las liquidaciones posteriores (Fund.clearFunds) también son v2: no reescriben el original
                update.set("financialSnapshot.originalAmount", p.clearedAmount());
            }
            if (p.currency() != null) update.set("currency", p.currency());
            if (p.campaignRef() != null) update.set("campaignRef", p.campaignRef());
        } else if (payload instanceof AllocationRequestedPayload p) {
            update.inc("financialSnapshot.pendingAllocationAmount", p.requestedAmount());
            DonationProjectionDocument.AllocationProjection alloc = new DonationProjectionDocument.AllocationProjection(p.allocationId(), null, null, p.requestedAmount(), "PENDING");
            update.push("allocations", alloc);
        } else if (payload instanceof AllocationConfirmedPayload p) {
            Optional<DonationProjectionDocument.AllocationProjection> opt = projection.getAllocations().stream().filter(a -> a.getAllocationId().equals(p.allocationId())).findFirst();
            if (opt.isEmpty()) {
                log.warn("AllocationConfirmed for non-existent allocationId: {}", p.allocationId());
            } else {
                long allocAmt = opt.get().getAmount();
                update.inc("financialSnapshot.pendingAllocationAmount", -allocAmt);
                update.set("allocations.$[elem].status", "CONFIRMED");
                update.filterArray(Criteria.where("elem.allocationId").is(p.allocationId()));
            }
        } else if (payload instanceof AllocationReversedPayload p) {
            Optional<DonationProjectionDocument.AllocationProjection> opt = projection.getAllocations().stream().filter(a -> a.getAllocationId().equals(p.allocationId())).findFirst();
            if (opt.isEmpty()) {
                log.warn("AllocationReversed for non-existent allocationId: {}", p.allocationId());
            } else {
                if ("PENDING".equals(opt.get().getStatus())) {
                    long allocAmt = opt.get().getAmount();
                    update.inc("financialSnapshot.pendingAllocationAmount", -allocAmt);
                }
                update.pull("allocations", new Query(Criteria.where("allocationId").is(p.allocationId())));
            }
        } else if (payload instanceof FundsRefundedPayload p) {
            update.inc("financialSnapshot.refundedAmount", p.refundAmount());
        }

        Query query = new Query(Criteria.where("_id").is(fundId));
        if (isNew) {
            // Insert
            projectionRepository.save(projection);
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        } else {
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        }
    }

    private void processPhysicalAssetEvent(TraceabilityEventDocument eventDoc, String assetId, long incomingSequence) {
        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType(), eventDoc.getSchemaVersion());
        if (routing.isInKindAsset(assetId, payload)) {
            // Camino B: ignorado de forma explícita hasta decidir su proyección (plan-b-proj.md §3.2).
            if (AssetProjectionRouting.registration(payload).isPresent()) {
                log.info("In-kind (camino B) asset {} not projected: no Fund to attach it to (B-PROJ)", assetId);
            }
            return;
        }
        undeclaredPayloads.checkDeclared(this, payload);
        Optional<AssetProjectionRouting.Registration> registration = AssetProjectionRouting.registration(payload);

        String projectionId = routing.resolveProjectionId(assetId, payload, true);
        if (projectionId == null) {
            throw new MissingDependencyException("Cannot resolve projectionId for asset " + assetId);
        }

        DonationProjectionDocument projection = projectionRepository.findById(projectionId).orElse(null);
        if (projection == null) {
            throw new MissingDependencyException("DonationProjection " + projectionId + " not found yet");
        }
        if ("PAUSED".equals(projection.getStatus())) {
            throw new ProjectionPausedException("Projection is PAUSED");
        }

        long lastProcessed = projection.getAuditMetadata().getAssetLastProcessedSequences().getOrDefault(assetId, 0L);
        if (incomingSequence <= lastProcessed) {
            return; // Duplicate
        }
        if (incomingSequence > lastProcessed + 1) {
            throw new SequenceGapException("Gap in PhysicalAsset stream " + assetId);
        }

        Update update = new Update();
        update.set("auditMetadata.assetLastProcessedSequences." + assetId, incomingSequence);

        if (registration.isPresent()) {
            AssetProjectionRouting.Registration p = registration.get();
            DonationProjectionDocument.LogisticsProjection log = new DonationProjectionDocument.LogisticsProjection(
                assetId, p.allocationId(), p.sourceAllocationId(), p.parentAssetRef(), p.rootAssetRef(),
                p.quantity(), p.unitOfMeasure(), p.assetType(), p.currentLocation(), p.custodianRef(), "REGISTERED", null,
                p.campaignRef() // D7: solo del payload v3 del propio activo
            );
            update.push("logistics", log);
        } else if (payload instanceof AssetDispatchedPayload p) {
            update.set("logistics.$[elem].lifecycleStatus", "DISPATCHED");
            update.set("logistics.$[elem].currentCustodian", p.carrierRef());
        } else if (payload instanceof AssetReceivedPayload p) {
            update.set("logistics.$[elem].lifecycleStatus", "RECEIVED");
            update.set("logistics.$[elem].currentLocation", p.facilityLocation());
            if (p.receiverRef() != null && !p.receiverRef().isEmpty()) {
                update.set("logistics.$[elem].currentCustodian", p.receiverRef());
            }
        } else if (payload instanceof AssetSplitV3Payload p) {
            update.set("logistics.$[elem].quantity", p.parentQuantityAfter());
            update.set("logistics.$[elem].statusBeforeSplit", p.statusBeforeSplit());
        } else if (payload instanceof AssetSplitV2Payload p) {
            update.set("logistics.$[elem].quantity", p.parentQuantityAfter());
            update.set("logistics.$[elem].statusBeforeSplit", p.statusBeforeSplit());
            // Child asset registration is handled by the ASSET_REGISTERED event of the child.
        } else if (payload instanceof AssetSplitPayload p) {
            update.set("logistics.$[elem].quantity", p.parentQuantityAfter());
            update.set("logistics.$[elem].statusBeforeSplit", p.statusBeforeSplit());
            // Child asset registration is handled by the ASSET_REGISTERED event of the child.
        } else if (payload instanceof AssetCustodyTransferredPayload p) {
            update.set("logistics.$[elem].currentCustodian", p.newCustodianRef());
        } else if (payload instanceof AssetSplitCompensatedPayload p) {
            DonationProjectionDocument.LogisticsProjection elem = projection.getLogistics().stream()
                .filter(l -> l.getAssetId().equals(assetId)).findFirst().orElse(null);
            if (elem != null && elem.getStatusBeforeSplit() != null) {
                update.set("logistics.$[elem].lifecycleStatus", elem.getStatusBeforeSplit());
            }
            update.inc("logistics.$[elem].quantity", new Decimal128(p.reintegratedQuantity()));
            update.set("logistics.$[elem].statusBeforeSplit", null);
        } else if (payload instanceof AssetDepletedPayload) {
            update.set("logistics.$[elem].lifecycleStatus", "DEPLETED");
        } else if (payload instanceof AssetDeliveredPayload p) {
            update.set("logistics.$[elem].currentLocation", p.locationRef());
            update.set("logistics.$[elem].currentCustodian", p.finalCustodianRef());
            update.set("logistics.$[elem].lifecycleStatus", "DELIVERED");
        }

        Query query = new Query(Criteria.where("_id").is(projectionId));
        if (registration.isEmpty()) {
            mongoTemplate.updateFirst(query, update.filterArray(Criteria.where("elem.assetId").is(assetId)), DonationProjectionDocument.class);
        } else {
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        }
        
        appendAssetHistory(eventDoc, assetId, payload);
    }

    private void appendAssetHistory(TraceabilityEventDocument eventDoc, String assetId, DomainEventPayload payload) {
        AssetHistoryProjectionDocument.AssetTransition transition = new AssetHistoryProjectionDocument.AssetTransition();
        transition.setSequence(eventDoc.getSequence());
        transition.setEventType(eventDoc.getEventType());
        transition.setTimestamp(eventDoc.getOccurredAt());
        
        Optional<AssetProjectionRouting.Registration> registration = AssetProjectionRouting.registration(payload);
        if (registration.isPresent()) {
            transition.setLocation(registration.get().currentLocation());
            transition.setCustodian(registration.get().custodianRef());
            transition.setStatus("REGISTERED");
        } else if (payload instanceof AssetDispatchedPayload p) {
            transition.setCustodian(p.carrierRef());
            transition.setStatus("DISPATCHED");
        } else if (payload instanceof AssetReceivedPayload p) {
            transition.setLocation(p.facilityLocation());
            transition.setStatus("RECEIVED");
            if (p.receiverRef() != null && !p.receiverRef().isEmpty()) {
                transition.setCustodian(p.receiverRef());
            }
        } else if (payload instanceof AssetSplitV3Payload || payload instanceof AssetSplitV2Payload
                || payload instanceof AssetSplitPayload) {
            transition.setStatus("SPLIT");
        } else if (payload instanceof AssetCustodyTransferredPayload p) {
            transition.setCustodian(p.newCustodianRef());
            transition.setStatus("CUSTODY_TRANSFERRED");
        } else if (payload instanceof AssetSplitCompensatedPayload) {
            transition.setStatus("SPLIT_COMPENSATED");
        } else if (payload instanceof AssetDepletedPayload) {
            transition.setStatus("DEPLETED");
        } else if (payload instanceof AssetDeliveredPayload p) {
            transition.setLocation(p.locationRef());
            transition.setCustodian(p.finalCustodianRef());
            transition.setStatus("DELIVERED");
        }

        Query q = new Query(Criteria.where("_id").is(assetId));
        Update u = new Update().push("transitions", transition);
        
        if (historyRepository.findById(assetId).isEmpty()) {
            AssetHistoryProjectionDocument doc = new AssetHistoryProjectionDocument();
            doc.setAssetId(assetId);
            historyRepository.save(doc);
        }
        mongoTemplate.updateFirst(q, u, AssetHistoryProjectionDocument.class);
    }

    public static class SequenceGapException extends RuntimeException {
        public SequenceGapException(String message) { super(message); }
    }
    public static class MissingDependencyException extends RuntimeException {
        public MissingDependencyException(String message) { super(message); }
    }
    public static class ProjectionPausedException extends RuntimeException {
        public ProjectionPausedException(String message) { super(message); }
    }
}
