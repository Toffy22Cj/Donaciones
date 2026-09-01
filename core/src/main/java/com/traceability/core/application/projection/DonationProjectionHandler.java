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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;

@Service
public class DonationProjectionHandler implements ProjectionEventHandler {

    @Override
    public String getHandlerName() {
        return "DonationProjectionHandler";
    }
    private final MongoTemplate mongoTemplate;
    private final EventCanonicalMapper canonicalMapper;
    private final AssetIndexRepository assetIndexRepository;
    private final DonationProjectionRepository projectionRepository;
    private final AssetHistoryProjectionRepository historyRepository;
    private final ProjectionRetryRepository retryRepository;

    public DonationProjectionHandler(MongoTemplate mongoTemplate,
                                     EventCanonicalMapper canonicalMapper,
                                     AssetIndexRepository assetIndexRepository,
                                     DonationProjectionRepository projectionRepository,
                                     AssetHistoryProjectionRepository historyRepository,
                                     ProjectionRetryRepository retryRepository) {
        this.mongoTemplate = mongoTemplate;
        this.canonicalMapper = canonicalMapper;
        this.assetIndexRepository = assetIndexRepository;
        this.projectionRepository = projectionRepository;
        this.historyRepository = historyRepository;
        this.retryRepository = retryRepository;
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
        try {
            processEvent(eventDoc);
        } catch (MissingDependencyException | SequenceGapException | ProjectionPausedException e) {
            enqueueForRetry(eventDoc);
        }
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
        
        long lastProcessed = projection != null ? projection.getAuditMetadata().getFundLastProcessedSequence() : -1;
        
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

        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType());

        // Update Snapshot and Allocations using MongoTemplate update for efficiency if exists, 
        // but for Fund it's easier to modify the object and save it since it's a single document
        // and we need to check idempotence locally first. Actually, ADR-010 requires positional updates,
        // but since we loaded it, we can just save it. Wait, the prompt says:
        // "Actualiza el documento DonationProjection correspondiente con operaciones posicionales ($set, $push) — NO reescribas el documento completo"
        
        Update update = new Update();
        update.set("auditMetadata.fundLastProcessedSequence", incomingSequence);

        if (payload instanceof FundRegisteredPayload p) {
            update.set("financialSnapshot.originalAmount", p.pledgedAmount() != null ? p.pledgedAmount() : 0);
        } else if (payload instanceof FundsClearedPayload p) {
            update.inc("financialSnapshot.clearedAmount", p.clearedAmount());
        } else if (payload instanceof AllocationRequestedPayload p) {
            update.inc("financialSnapshot.pendingAllocationAmount", p.requestedAmount());
            DonationProjectionDocument.AllocationProjection alloc = new DonationProjectionDocument.AllocationProjection(p.allocationId(), null, null, p.requestedAmount());
            update.push("allocations", alloc);
        } else if (payload instanceof AllocationConfirmedPayload p) {
            // Find allocation amount to move from pending to allocated
            // We need to query the current allocation amount. It's complex in a single update.
            // But we can just use the loaded projection to calculate amounts.
            long allocAmt = projection.getAllocations().stream().filter(a -> a.getAllocationId().equals(p.allocationId())).findFirst().map(a -> a.getAmount()).orElse(0L);
            update.inc("financialSnapshot.pendingAllocationAmount", -allocAmt);
        } else if (payload instanceof AllocationReversedPayload p) {
            long allocAmt = projection.getAllocations().stream().filter(a -> a.getAllocationId().equals(p.allocationId())).findFirst().map(a -> a.getAmount()).orElse(0L);
            update.inc("financialSnapshot.pendingAllocationAmount", -allocAmt);
            update.pull("allocations", new Query(Criteria.where("allocationId").is(p.allocationId())));
        } else if (payload instanceof FundsRefundedPayload p) {
            update.inc("financialSnapshot.refundedAmount", p.refundAmount());
        }

        Query query = new Query(Criteria.where("_id").is(fundId));
        if (projection.getAuditMetadata().getFundLastProcessedSequence() == 0 && projectionRepository.findById(fundId).isEmpty()) {
            // Insert
            projectionRepository.save(projection);
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        } else {
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        }
    }

    private void processPhysicalAssetEvent(TraceabilityEventDocument eventDoc, String assetId, long incomingSequence) {
        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType());
        
        String projectionId = resolveProjectionId(assetId, payload);
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

        long lastProcessed = projection.getAuditMetadata().getAssetLastProcessedSequences().getOrDefault(assetId, -1L);
        if (incomingSequence <= lastProcessed) {
            return; // Duplicate
        }
        if (incomingSequence > lastProcessed + 1) {
            throw new SequenceGapException("Gap in PhysicalAsset stream " + assetId);
        }

        Update update = new Update();
        update.set("auditMetadata.assetLastProcessedSequences." + assetId, incomingSequence);

        if (payload instanceof AssetRegisteredPayload p) {
            DonationProjectionDocument.LogisticsProjection log = new DonationProjectionDocument.LogisticsProjection(
                assetId, p.allocationId(), p.sourceAllocationId(), p.parentAssetRef(), p.rootAssetRef(), 
                p.quantity(), p.unitOfMeasure(), p.assetType(), p.currentLocation(), p.custodianRef(), "REGISTERED"
            );
            update.push("logistics", log);
        } else if (payload instanceof AssetDispatchedPayload p) {
            update.set("logistics.$[elem].lifecycleStatus", "DISPATCHED");
            update.set("logistics.$[elem].currentCustodian", p.carrierRef());
        } else if (payload instanceof AssetReceivedPayload p) {
            update.set("logistics.$[elem].lifecycleStatus", "RECEIVED");
            update.set("logistics.$[elem].currentLocation", p.facilityLocation());
        } else if (payload instanceof AssetSplitPayload p) {
            update.set("logistics.$[elem].quantity", p.parentQuantityAfter());
            // Child asset registration is handled by the ASSET_REGISTERED event of the child.
        }

        Query query = new Query(Criteria.where("_id").is(projectionId));
        if (!(payload instanceof AssetRegisteredPayload)) {
            mongoTemplate.updateFirst(query, update.filterArray(Criteria.where("elem.assetId").is(assetId)), DonationProjectionDocument.class);
        } else {
            mongoTemplate.updateFirst(query, update, DonationProjectionDocument.class);
        }
        
        appendAssetHistory(eventDoc, assetId, payload);
    }

    private String resolveProjectionId(String assetId, DomainEventPayload payload) {
        // First check asset_index
        AssetIndexDocument index = assetIndexRepository.findById(assetId).orElse(null);
        if (index != null) {
            return index.getProjectionId();
        }

        // If not in index, it must be an ASSET_REGISTERED event
        if (payload instanceof AssetRegisteredPayload regPayload) {
            if (regPayload.parentAssetRef() == null) {
                // Root asset: lookup fundId via allocationId
                Query q = new Query(Criteria.where("allocations.allocationId").is(regPayload.allocationId()));
                q.fields().include("_id");
                DonationProjectionDocument doc = mongoTemplate.findOne(q, DonationProjectionDocument.class);
                if (doc != null) {
                    AssetIndexDocument newIndex = new AssetIndexDocument(assetId, doc.getProjectionId(), assetId, 0);
                    assetIndexRepository.save(newIndex);
                    return doc.getProjectionId();
                }
            } else {
                // Child asset: lookup projectionId from parent's index
                AssetIndexDocument parentIndex = assetIndexRepository.findById(regPayload.parentAssetRef()).orElse(null);
                if (parentIndex != null) {
                    AssetIndexDocument newIndex = new AssetIndexDocument(assetId, parentIndex.getProjectionId(), parentIndex.getRootAssetRef(), 0);
                    assetIndexRepository.save(newIndex);
                    return parentIndex.getProjectionId();
                }
            }
        }
        return null;
    }

    private void appendAssetHistory(TraceabilityEventDocument eventDoc, String assetId, DomainEventPayload payload) {
        AssetHistoryProjectionDocument.AssetTransition transition = new AssetHistoryProjectionDocument.AssetTransition();
        transition.setSequence(eventDoc.getSequence());
        transition.setEventType(eventDoc.getEventType());
        transition.setTimestamp(eventDoc.getOccurredAt());
        
        if (payload instanceof AssetRegisteredPayload p) {
            transition.setLocation(p.currentLocation());
            transition.setCustodian(p.custodianRef());
            transition.setStatus("REGISTERED");
        } else if (payload instanceof AssetDispatchedPayload p) {
            transition.setCustodian(p.carrierRef());
            transition.setStatus("DISPATCHED");
        } else if (payload instanceof AssetReceivedPayload p) {
            transition.setLocation(p.facilityLocation());
            transition.setStatus("RECEIVED");
        } else if (payload instanceof AssetSplitPayload) {
            transition.setStatus("SPLIT");
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

    private void enqueueForRetry(TraceabilityEventDocument eventDoc) {
        ProjectionRetryDocument retryDoc = new ProjectionRetryDocument();
        retryDoc.setId(eventDoc.getEventId() + "_" + getHandlerName());
        retryDoc.setHandlerName(getHandlerName());
        retryDoc.setEventId(eventDoc.getEventId());
        retryDoc.setStreamId(eventDoc.getStreamId());
        retryDoc.setSequence(eventDoc.getSequence());
        retryDoc.setEventType(eventDoc.getEventType());
        retryDoc.setPayload(eventDoc.getPayload());
        retryDoc.setOccurredAt(eventDoc.getOccurredAt());
        retryDoc.setFirstAttemptAt(Instant.now().toString());
        retryDoc.setLastAttemptAt(Instant.now().toString());
        
        // PAUSED rule: if we can determine the projectionId and it is PAUSED, quarantine immediately.
        String projectionId = null;
        if ("Fund".equals(eventDoc.getAggregateType())) {
            projectionId = eventDoc.getStreamId();
        } else {
            try {
                DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType());
                projectionId = resolveProjectionId(eventDoc.getStreamId(), payload);
            } catch (Exception ignored) {}
        }
        
        retryDoc.setProjectionId(projectionId);
        
        if (projectionId != null) {
            DonationProjectionDocument proj = projectionRepository.findById(projectionId).orElse(null);
            if (proj != null && "PAUSED".equals(proj.getStatus())) {
                retryDoc.setStatus("QUARANTINED");
            }
        }
        
        retryRepository.save(retryDoc);
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
