package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.AllocationStatus;
import com.traceability.core.domain.fund.payloads.AllocationConfirmedPayload;
import com.traceability.core.domain.fund.payloads.AllocationRequestedPayload;
import com.traceability.core.domain.fund.payloads.AllocationReversedPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import com.traceability.core.infrastructure.projection.mongo.documents.PendingAllocationDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.PendingAllocationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;

@Component
public class PendingAllocationProjectionHandler implements ProjectionEventHandler {

    private final EventCanonicalMapper canonicalMapper;
    private final PendingAllocationRepository repository;

    private final ProjectionRetryRepository retryRepository;

    public PendingAllocationProjectionHandler(EventCanonicalMapper canonicalMapper, PendingAllocationRepository repository, ProjectionRetryRepository retryRepository) {
        this.canonicalMapper = canonicalMapper;
        this.repository = repository;
        this.retryRepository = retryRepository;
    }

    @Override
    public String getHandlerName() {
        return "PendingAllocationProjectionHandler";
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
        try {
            processEvent(eventDoc);
        } catch (DonationProjectionHandler.MissingDependencyException | DonationProjectionHandler.SequenceGapException | DonationProjectionHandler.ProjectionPausedException e) {
            enqueueForRetry(eventDoc);
        }
    }

    void processEvent(TraceabilityEventDocument eventDoc) {
        if (!"Fund".equals(eventDoc.getAggregateType())) {
            return;
        }

        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType(), eventDoc.getSchemaVersion());

        if (payload instanceof AllocationRequestedPayload p) {
            if (repository.existsById(p.allocationId())) {
                return; // Idempotency: skip if already exists
            }
            PendingAllocationDocument doc = PendingAllocationDocument.builder()
                .allocationId(p.allocationId())
                .fundId(eventDoc.getStreamId())
                .requestedAmount(p.requestedAmount())
                .requestedAt(eventDoc.getOccurredAt() != null ? Instant.parse(eventDoc.getOccurredAt()) : null)
                .status(AllocationStatus.REQUESTED)
                .build();
            repository.save(doc);
        } else if (payload instanceof AllocationConfirmedPayload p) {
            PendingAllocationDocument doc = repository.findById(p.allocationId()).orElse(null);
            if (doc == null) {
                throw new DonationProjectionHandler.MissingDependencyException("PendingAllocation " + p.allocationId() + " not found yet");
            }
            doc.setStatus(AllocationStatus.CONFIRMED);
            repository.save(doc);
        } else if (payload instanceof AllocationReversedPayload p) {
            PendingAllocationDocument doc = repository.findById(p.allocationId()).orElse(null);
            if (doc == null) {
                throw new DonationProjectionHandler.MissingDependencyException("PendingAllocation " + p.allocationId() + " not found yet");
            }
            doc.setStatus(AllocationStatus.REVERSED);
            repository.save(doc);
        }
    }

    private void enqueueForRetry(TraceabilityEventDocument eventDoc) {
        ProjectionRetryDocument retryDoc = ProjectionRetryDocument.builder()
            .id(eventDoc.getEventId() + "_" + getHandlerName())
            .handlerName(getHandlerName())
            .eventId(eventDoc.getEventId())
            .streamId(eventDoc.getStreamId())
            .sequence(eventDoc.getSequence())
            .eventType(eventDoc.getEventType())
            .schemaVersion(eventDoc.getSchemaVersion())
            .payload(eventDoc.getPayload())
            .occurredAt(eventDoc.getOccurredAt())
            .firstAttemptAt(Instant.now().toString())
            .lastAttemptAt(Instant.now().toString())
            .build();
        retryRepository.save(retryDoc);
    }
}
