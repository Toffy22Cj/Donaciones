package com.traceability.core.application.command;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PhysicalAssetCommandService {

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;

    public PhysicalAssetCommandService(CommandRetryTemplate retryTemplate,
                                       ProcessedCommandRepositoryPort processedCommandRepository,
                                       EventStorePort eventStore,
                                       TransactionalEventPublisher eventPublisher) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
    }

    public void deliverAsset(String commandId, String assetId, String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef, Instant deliveredAt) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(assetId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, payloads, events.size());
            long expectedVersion = asset.getVersion();
            
            asset.deliver(finalCustodianRef, beneficiaryRef, locationRef, evidenceRef, deliveredAt);
            
            List<DomainEvent> newEvents = asset.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(assetId, "PhysicalAsset", expectedVersion, newEvents, "SYSTEM", null, commandId);
            }
            return null;
        });
    }

    // Other methods...
}
