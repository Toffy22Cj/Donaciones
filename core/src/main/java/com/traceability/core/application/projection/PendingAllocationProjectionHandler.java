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

@Component
public class PendingAllocationProjectionHandler implements ProjectionEventHandler {

    private final EventCanonicalMapper canonicalMapper;
    private final PendingAllocationRepository repository;

    public PendingAllocationProjectionHandler(EventCanonicalMapper canonicalMapper, PendingAllocationRepository repository) {
        this.canonicalMapper = canonicalMapper;
        this.repository = repository;
    }

    @Override
    public String getHandlerName() {
        return "PendingAllocationProjectionHandler";
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
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
            Optional<PendingAllocationDocument> docOpt = repository.findById(p.allocationId());
            docOpt.ifPresent(doc -> {
                doc.setStatus(AllocationStatus.CONFIRMED);
                repository.save(doc);
            });
        } else if (payload instanceof AllocationReversedPayload p) {
            Optional<PendingAllocationDocument> docOpt = repository.findById(p.allocationId());
            docOpt.ifPresent(doc -> {
                doc.setStatus(AllocationStatus.REVERSED);
                repository.save(doc);
            });
        }
    }
}
