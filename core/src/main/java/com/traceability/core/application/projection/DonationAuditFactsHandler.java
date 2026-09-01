package com.traceability.core.application.projection;

import com.traceability.contracts.FinancialFlagDTO;
import com.traceability.contracts.TransitionFactDTO;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsRefundedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationAuditFactsDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.properties.AuditThresholdProperties;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationAuditFactsRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@Slf4j
public class DonationAuditFactsHandler implements ProjectionEventHandler {

    private final MongoTemplate mongoTemplate;
    private final EventCanonicalMapper canonicalMapper;
    private final AssetIndexRepository assetIndexRepository;
    private final DonationAuditFactsRepository auditFactsRepository;
    private final ProjectionRetryRepository retryRepository;
    private final AuditThresholdProperties thresholds;

    public DonationAuditFactsHandler(MongoTemplate mongoTemplate,
                                     EventCanonicalMapper canonicalMapper,
                                     AssetIndexRepository assetIndexRepository,
                                     DonationAuditFactsRepository auditFactsRepository,
                                     ProjectionRetryRepository retryRepository,
                                     AuditThresholdProperties thresholds) {
        this.mongoTemplate = mongoTemplate;
        this.canonicalMapper = canonicalMapper;
        this.assetIndexRepository = assetIndexRepository;
        this.auditFactsRepository = auditFactsRepository;
        this.retryRepository = retryRepository;
        this.thresholds = thresholds;
    }

    @Override
    public String getHandlerName() {
        return "DonationAuditFactsHandler";
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
        try {
            processEvent(eventDoc);
        } catch (DonationProjectionHandler.MissingDependencyException | DonationProjectionHandler.SequenceGapException e) {
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
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElse(null);
        long lastProcessed = doc != null ? doc.getAuditMetadata().getFundLastProcessedSequence() : -1;

        if (incomingSequence <= lastProcessed) {
            return; // Duplicate
        }
        if (incomingSequence > lastProcessed + 1) {
            throw new DonationProjectionHandler.SequenceGapException("Gap in Fund stream " + fundId);
        }

        if (doc == null) {
            doc = new DonationAuditFactsDocument();
            doc.setFundId(fundId);
            doc.setGeneratedAt(Instant.now());
        }

        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType());
        Update update = new Update();
        update.set("auditMetadata.fundLastProcessedSequence", incomingSequence);
        update.set("generatedAt", Instant.now());
        
        boolean shouldUpdate = true;

        if (payload instanceof FundsRefundedPayload p) {
            if (p.causedDeficit()) {
                FinancialFlagDTO flag = new FinancialFlagDTO("DEFICIT_REFUND", null, p.refundId(), true, p.refundAmount(), Instant.parse(eventDoc.getOccurredAt()));
                update.push("financialFlags", flag);
            } else {
                shouldUpdate = false; // We don't record non-deficit refunds in audit facts
            }
        } else {
            shouldUpdate = false; // We don't care about other Fund events for audit facts currently
        }

        if (doc.getAuditMetadata().getFundLastProcessedSequence() == 0 && auditFactsRepository.findById(fundId).isEmpty()) {
            auditFactsRepository.save(doc);
        }
        
        // Even if shouldUpdate is false for the payload, we still update the sequence
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(fundId)), update, DonationAuditFactsDocument.class);
    }

    private void processPhysicalAssetEvent(TraceabilityEventDocument eventDoc, String assetId, long incomingSequence) {
        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType());
        
        String fundId = resolveFundId(assetId, payload);
        if (fundId == null) {
            throw new DonationProjectionHandler.MissingDependencyException("Cannot resolve fundId for asset " + assetId);
        }

        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElse(null);
        if (doc == null) {
            // Fund might not be processed yet, or we create it on the fly
            doc = new DonationAuditFactsDocument();
            doc.setFundId(fundId);
            doc.setGeneratedAt(Instant.now());
            auditFactsRepository.save(doc);
        }

        long lastProcessed = doc.getAuditMetadata().getAssetLastProcessedSequences().getOrDefault(assetId, -1L);
        if (incomingSequence <= lastProcessed) {
            return; // Duplicate
        }
        if (incomingSequence > lastProcessed + 1) {
            throw new DonationProjectionHandler.SequenceGapException("Gap in PhysicalAsset stream " + assetId);
        }

        Update update = new Update();
        update.set("auditMetadata.assetLastProcessedSequences." + assetId, incomingSequence);
        update.set("generatedAt", Instant.now());

        Instant eventTime = Instant.parse(eventDoc.getOccurredAt());

        if (payload instanceof AssetDispatchedPayload) {
            // Re-despacho: remove orphaned RECEIVED->?
            Update pullUpdate = new Update();
            pullUpdate.pull("transitions", new Document("assetRef", assetId)
                    .append("fromStatus", "RECEIVED")
                    .append("toStatus", null));
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(fundId)), pullUpdate, DonationAuditFactsDocument.class);
            
            // Open new DISPATCHED->?
            TransitionFactDTO newTransition = new TransitionFactDTO(assetId, "DISPATCHED", null, eventTime, null, 0, null, false);
            update.push("transitions", newTransition);

        } else if (payload instanceof AssetReceivedPayload) {
            // Close DISPATCHED->RECEIVED if exists
            int targetIndex = findPendingTransitionIndex(doc, assetId, "DISPATCHED");
            if (targetIndex >= 0) {
                TransitionFactDTO pending = doc.getTransitions().get(targetIndex);
                long duration = ChronoUnit.SECONDS.between(pending.occurredAtFrom(), eventTime);
                long expectedMax = thresholds.getDispatchedToReceived();
                boolean anomaly = duration > expectedMax;

                Update setUpdate = new Update();
                setUpdate.set("transitions." + targetIndex + ".toStatus", "RECEIVED");
                setUpdate.set("transitions." + targetIndex + ".occurredAtTo", eventTime);
                setUpdate.set("transitions." + targetIndex + ".durationSeconds", duration);
                setUpdate.set("transitions." + targetIndex + ".expectedMaximumSeconds", expectedMax);
                setUpdate.set("transitions." + targetIndex + ".anomaly", anomaly);
                mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(fundId)), setUpdate, DonationAuditFactsDocument.class);
            }
            
            // AND open new RECEIVED->?
            TransitionFactDTO newTransition = new TransitionFactDTO(assetId, "RECEIVED", null, eventTime, null, 0, null, false);
            update.push("transitions", newTransition);

        } else if (payload instanceof AssetDeliveredPayload) {
            // Close any pending (DISPATCHED or RECEIVED)
            int targetIndex = findMostRecentPendingTransitionIndex(doc, assetId);
            if (targetIndex >= 0) {
                TransitionFactDTO pending = doc.getTransitions().get(targetIndex);
                long expectedMax = pending.fromStatus().equals("DISPATCHED") 
                        ? thresholds.getDispatchedToDelivered() 
                        : thresholds.getReceivedToDelivered();
                long duration = ChronoUnit.SECONDS.between(pending.occurredAtFrom(), eventTime);
                boolean anomaly = duration > expectedMax;

                update.set("transitions." + targetIndex + ".toStatus", "DELIVERED");
                update.set("transitions." + targetIndex + ".occurredAtTo", eventTime);
                update.set("transitions." + targetIndex + ".durationSeconds", duration);
                update.set("transitions." + targetIndex + ".expectedMaximumSeconds", expectedMax);
                update.set("transitions." + targetIndex + ".anomaly", anomaly);
            } else {
                log.warn("DELIVERED event received for asset {} but no pending transition found. Ignoring silently.", assetId);
            }
        }

        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(fundId)), update, DonationAuditFactsDocument.class);
    }

    private int findPendingTransitionIndex(DonationAuditFactsDocument doc, String assetId, String fromStatus) {
        for (int i = doc.getTransitions().size() - 1; i >= 0; i--) {
            TransitionFactDTO t = doc.getTransitions().get(i);
            if (t.assetRef().equals(assetId) && t.toStatus() == null && t.fromStatus().equals(fromStatus)) {
                return i;
            }
        }
        return -1;
    }

    private int findMostRecentPendingTransitionIndex(DonationAuditFactsDocument doc, String assetId) {
        for (int i = doc.getTransitions().size() - 1; i >= 0; i--) {
            TransitionFactDTO t = doc.getTransitions().get(i);
            if (t.assetRef().equals(assetId) && t.toStatus() == null) {
                return i;
            }
        }
        return -1;
    }

    private String resolveFundId(String assetId, DomainEventPayload payload) {
        AssetIndexDocument index = assetIndexRepository.findById(assetId).orElse(null);
        if (index != null) {
            return index.getProjectionId();
        }

        if (payload instanceof AssetRegisteredPayload regPayload) {
            if (regPayload.parentAssetRef() == null) {
                // For root assets, we might need to rely on the AssetIndex being populated by DonationProjectionHandler
                // Since DonationProjectionHandler and AuditFactsHandler run asynchronously, there's a race condition.
                // We should throw MissingDependencyException to wait for DonationProjectionHandler to populate the index.
                return null; 
            } else {
                AssetIndexDocument parentIndex = assetIndexRepository.findById(regPayload.parentAssetRef()).orElse(null);
                if (parentIndex != null) {
                    return parentIndex.getProjectionId();
                }
            }
        }
        return null;
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
        retryRepository.save(retryDoc);
    }
}
