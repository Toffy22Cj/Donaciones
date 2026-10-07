package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.AllocationStatus;
import com.traceability.core.domain.fund.payloads.AllocationConfirmedPayload;
import com.traceability.core.domain.fund.payloads.AllocationRequestedPayload;
import com.traceability.core.domain.fund.payloads.AllocationReversedPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import com.traceability.core.infrastructure.projection.mongo.documents.PendingAllocationDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.PendingAllocationRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;


@Component
@Order(20)
public class PendingAllocationProjectionHandler implements ProjectionEventHandler {

    private static final Set<Class<? extends DomainEventPayload>> HANDLED = Set.of(
            AllocationRequestedPayload.class, AllocationConfirmedPayload.class, AllocationReversedPayload.class);

    /** El resto del registro, sin efecto (este manejador no lleva secuencia). */
    private static final Set<Class<? extends DomainEventPayload>> IGNORED = ignoredFromRegistry();

    private final EventCanonicalMapper canonicalMapper;
    private final PendingAllocationRepository repository;
    private final UndeclaredPayloadMonitor undeclaredPayloads;

    public PendingAllocationProjectionHandler(EventCanonicalMapper canonicalMapper, PendingAllocationRepository repository,
                                              UndeclaredPayloadMonitor undeclaredPayloads) {
        this.canonicalMapper = canonicalMapper;
        this.repository = repository;
        this.undeclaredPayloads = undeclaredPayloads;
    }

    private static Set<Class<? extends DomainEventPayload>> ignoredFromRegistry() {
        Set<Class<? extends DomainEventPayload>> ignored = new HashSet<>(EventPayloadRegistry.registeredPayloads().values());
        ignored.removeAll(HANDLED);
        return Set.copyOf(ignored);
    }

    @Override
    public String getHandlerName() {
        return "PendingAllocationProjectionHandler";
    }

    @Override
    public Set<Class<? extends DomainEventPayload>> handledPayloads() {
        return HANDLED;
    }

    @Override
    public Set<Class<? extends DomainEventPayload>> ignoredPayloads() {
        return IGNORED;
    }

    @Override
    public void handleEvent(TraceabilityEventDocument eventDoc) {
        processEvent(eventDoc);
    }

    void processEvent(TraceabilityEventDocument eventDoc) {
        if (!"Fund".equals(eventDoc.getAggregateType())) {
            return;
        }

        DomainEventPayload payload = canonicalMapper.convertPayload(eventDoc.getPayload(), eventDoc.getEventType(), eventDoc.getSchemaVersion());
        undeclaredPayloads.checkDeclared(this, payload);

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
}
