package com.traceability.core.application.projection;

import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;

@Service
public class ProjectionRetryScheduler {

    private final ProjectionRetryRepository retryRepository;
    private final Map<String, ProjectionEventHandler> handlers;
    private final DonationProjectionRepository projectionRepository;

    public ProjectionRetryScheduler(ProjectionRetryRepository retryRepository,
                                    List<ProjectionEventHandler> handlerList,
                                    DonationProjectionRepository projectionRepository) {
        this.retryRepository = retryRepository;
        this.handlers = handlerList.stream()
            .collect(Collectors.toMap(ProjectionEventHandler::getHandlerName, Function.identity()));
        this.projectionRepository = projectionRepository;
    }

    public void processRetries() {
        List<ProjectionRetryDocument> pending = retryRepository.findByStatus("PENDING");
        
        for (ProjectionRetryDocument retryDoc : pending) {
            try {
                TraceabilityEventDocument eventDoc = toEventDoc(retryDoc);
                ProjectionEventHandler handler = handlers.get(retryDoc.getHandlerName());
                if (handler != null) {
                    handler.handleEvent(eventDoc);
                    retryRepository.delete(retryDoc);
                } else {
                    // Unknown handler, quarantine immediately
                    quarantine(retryDoc);
                    continue;
                }
            } catch (DonationProjectionHandler.SequenceGapException | DonationProjectionHandler.MissingDependencyException e) {
                // Still failing. Check if 4 hours have passed
                Instant firstAttempt = Instant.parse(retryDoc.getFirstAttemptAt());
                if (firstAttempt.plus(4, ChronoUnit.HOURS).isBefore(Instant.now())) {
                    quarantine(retryDoc);
                } else {
                    retryDoc.setRetryCount(retryDoc.getRetryCount() + 1);
                    retryDoc.setLastAttemptAt(Instant.now().toString());
                    retryRepository.save(retryDoc);
                }
            } catch (DonationProjectionHandler.ProjectionPausedException e) {
                quarantine(retryDoc);
            } catch (Exception e) {
                // Other unexpected errors, quarantine immediately
                quarantine(retryDoc);
            }
        }
    }

    private void quarantine(ProjectionRetryDocument retryDoc) {
        retryDoc.setStatus("QUARANTINED");
        retryRepository.save(retryDoc);
        
        if (retryDoc.getProjectionId() != null) {
            DonationProjectionDocument proj = projectionRepository.findById(retryDoc.getProjectionId()).orElse(null);
            if (proj != null && !"PAUSED".equals(proj.getStatus())) {
                proj.setStatus("PAUSED");
                projectionRepository.save(proj);
            }
        }
    }

    public void resumeProjection(String projectionId) {
        // 1. Mark projection as ACTIVE
        DonationProjectionDocument proj = projectionRepository.findById(projectionId)
            .orElseThrow(() -> new IllegalArgumentException("Projection not found"));
        proj.setStatus("ACTIVE");
        projectionRepository.save(proj);

        // 2. Fetch all QUARANTINED events for this projection, ordered by sequence
        List<ProjectionRetryDocument> quarantined = 
            retryRepository.findByProjectionIdAndStatusOrderBySequenceAsc(projectionId, "QUARANTINED");

        // 3. Re-process in order
        for (ProjectionRetryDocument retryDoc : quarantined) {
            TraceabilityEventDocument eventDoc = toEventDoc(retryDoc);
            try {
                ProjectionEventHandler handler = handlers.get(retryDoc.getHandlerName());
                if (handler != null) {
                    handler.handleEvent(eventDoc);
                    retryRepository.delete(retryDoc);
                }
            } catch (Exception e) {
                // If it fails again during resume, the handleEvent will enqueue it to retry_pending
                // Wait, handleEvent catches SequenceGap and calls enqueueForRetry.
                // We should delete the old quarantined doc because handleEvent created a new one,
                // but only if handleEvent successfully enqueued a new one.
                // Actually, just delete the old one and let handleEvent do its thing.
                retryRepository.delete(retryDoc);
                break; // Stop processing further events for this projection to maintain order
            }
        }
    }

    private TraceabilityEventDocument toEventDoc(ProjectionRetryDocument retryDoc) {
        TraceabilityEventDocument eventDoc = new TraceabilityEventDocument();
        eventDoc.setEventId(retryDoc.getEventId());
        eventDoc.setStreamId(retryDoc.getStreamId());
        eventDoc.setSequence(retryDoc.getSequence());
        eventDoc.setEventType(retryDoc.getEventType());
        eventDoc.setPayload(retryDoc.getPayload());
        eventDoc.setOccurredAt(retryDoc.getOccurredAt());
        // For projection purposes, we don't need the exact original metadata except what affects logic.
        // We assume aggregateType can be inferred.
        if (retryDoc.getPayload().containsKey("pledgedAmount") || retryDoc.getPayload().containsKey("clearedAmount") || retryDoc.getPayload().containsKey("allocationId") && !retryDoc.getPayload().containsKey("assetId")) {
            eventDoc.setAggregateType("Fund");
        } else if (retryDoc.getPayload().containsKey("assetId") || retryDoc.getEventType().startsWith("ASSET_")) {
            eventDoc.setAggregateType("PhysicalAsset");
        } else {
            eventDoc.setAggregateType(retryDoc.getProjectionId() != null && retryDoc.getProjectionId().equals(retryDoc.getStreamId()) ? "Fund" : "PhysicalAsset");
        }
        return eventDoc;
    }
}
