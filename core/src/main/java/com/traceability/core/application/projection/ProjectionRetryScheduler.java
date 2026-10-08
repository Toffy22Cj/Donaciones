package com.traceability.core.application.projection;

import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedResource;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;

@Service
@ManagedResource(objectName = "com.traceability.core.application.projection:type=ProjectionRetryScheduler", description = "Scheduler for Projection Retries")
public class ProjectionRetryScheduler {

    private final ProjectionRetryRepository retryRepository;
    private final Map<String, ProjectionEventHandler> handlers;
    private final DonationProjectionRepository projectionRepository;
    private final UndeclaredPayloadMonitor undeclaredPayloads;

    public ProjectionRetryScheduler(ProjectionRetryRepository retryRepository,
                                    List<ProjectionEventHandler> handlerList,
                                    DonationProjectionRepository projectionRepository,
                                    UndeclaredPayloadMonitor undeclaredPayloads) {
        this.retryRepository = retryRepository;
        this.handlers = handlerList.stream()
            .collect(Collectors.toMap(ProjectionEventHandler::getHandlerName, Function.identity()));
        this.projectionRepository = projectionRepository;
        this.undeclaredPayloads = undeclaredPayloads;
    }

    /** B-PROJ: payloads no declarados por algún manejador (log de error en cada uno). Debe ser 0. */
    @ManagedAttribute(description = "Payloads that reached a projection handler without being declared (should be 0)")
    public long getUndeclaredPayloadCount() {
        return undeclaredPayloads.total();
    }

    @ManagedAttribute(description = "Undeclared payloads per projection handler")
    public String getUndeclaredPayloadCountByHandler() {
        return undeclaredPayloads.byHandler().toString();
    }

    @Value("${core.projection.retry.timeout-minutes:5}")
    private int processingTimeoutMinutes;

    @Scheduled(fixedDelayString = "${core.projection.retry.delay:60000}")
    public void processRetries() {
        // Each document is attempted at most once per run: retryable failures stay PROCESSING (not claimable)
        // until the run ends and are only then released to PENDING, so the scheduler's fixedDelay is the
        // backoff. Releasing them immediately made the loop re-claim the same document without pause.
        List<ProjectionRetryDocument> deferred = new ArrayList<>();
        Set<String> attemptedThisRun = new HashSet<>();
        try {
            while (true) {
                Optional<ProjectionRetryDocument> optRetryDoc = retryRepository.claimNextPendingRetry(processingTimeoutMinutes);
                if (optRetryDoc.isEmpty()) {
                    break;
                }

                ProjectionRetryDocument retryDoc = optRetryDoc.get();
                if (!attemptedThisRun.add(retryDoc.getId())) {
                    // Re-claimed within this run (processing lease expired): leave it for the next run.
                    deferred.add(retryDoc);
                    continue;
                }
                try {
                    TraceabilityEventDocument eventDoc = toEventDoc(retryDoc);
                    ProjectionEventHandler handler = handlers.get(retryDoc.getHandlerName());
                    if (handler != null) {
                        handler.handleEvent(eventDoc);
                        retryRepository.delete(retryDoc);
                    } else {
                        // Unknown handler, quarantine immediately
                        quarantine(retryDoc);
                    }
                } catch (Exception e) {
                    boolean isPermanent = isPermanentError(e);
                    if (isPermanent) {
                        quarantine(retryDoc);
                    } else {
                        // Still failing with retryable error. Check if 4 hours have passed
                        Instant firstAttempt = Instant.parse(retryDoc.getFirstAttemptAt());
                        if (firstAttempt.plus(4, ChronoUnit.HOURS).isBefore(Instant.now())) {
                            quarantine(retryDoc);
                        } else {
                            retryDoc.setRetryCount(retryDoc.getRetryCount() + 1);
                            retryDoc.setLastAttemptAt(Instant.now().toString());
                            deferred.add(retryDoc);
                        }
                    }
                }
            }
        } finally {
            for (ProjectionRetryDocument retryDoc : deferred) {
                retryDoc.setStatus("PENDING"); // Revert back to PENDING so the next run can claim it again
                retryDoc.setProcessingStartedAt(null);
                retryRepository.save(retryDoc);
            }
        }
    }

    private boolean isPermanentError(Exception e) {
        if (e instanceof com.traceability.core.application.projection.DonationProjectionHandler.SequenceGapException ||
            e instanceof com.traceability.core.application.projection.DonationProjectionHandler.MissingDependencyException ||
            e instanceof org.springframework.dao.DataAccessException ||
            e.getClass().getName().contains("MongoSocketException") ||
            e.getClass().getName().contains("MongoTimeoutException")) {
            return false; // Retryable
        }
        return true; // NullPointerException, IllegalArgumentException, ProjectionPausedException, etc are permanent
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

    @ManagedOperation(description = "Resumes a paused projection by un-quarantining its pending events and changing status to ACTIVE")
    @ManagedOperationParameter(name = "projectionId", description = "The ID of the projection to resume")
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
                // Handlers no longer enqueue their own retries (A7.2), so this document is the only copy of
                // the event: keep it QUARANTINED and pause the projection again instead of deleting it.
                quarantine(retryDoc);
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
        eventDoc.setSchemaVersion(retryDoc.getSchemaVersion());
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
