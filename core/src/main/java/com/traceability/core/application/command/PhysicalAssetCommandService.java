package com.traceability.core.application.command;

import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.ActorRef;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.ExternalActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PhysicalAssetCommandService {

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;
    private final RoleAuthorizationPolicy roleAuthorizationPolicy;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;

    public PhysicalAssetCommandService(CommandRetryTemplate retryTemplate,
            ProcessedCommandRepositoryPort processedCommandRepository,
            EventStorePort eventStore,
            TransactionalEventPublisher eventPublisher,
            RoleAuthorizationPolicy roleAuthorizationPolicy,
            OrganizationBoundaryPolicy organizationBoundaryPolicy) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
        this.roleAuthorizationPolicy = roleAuthorizationPolicy;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
    }

    private void authorize(ActorRef actorRef, String organizationRef) {
        switch (actorRef) {
            case SystemActor sa -> {
                // bypass P7/P9
            }
            case ExternalActor ea -> {
                // bypass P7/P9
            }
            // NO default branch. When HumanAccount is introduced, compiler will enforce revisiting this switch.
        }
    }

    public void deliverAsset(String commandId, String assetId, String finalCustodianRef, String beneficiaryRef,
            String locationRef, String evidenceRef, Instant deliveredAt,
            com.traceability.core.domain.event.ActorRef actorRef) {
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
                eventPublisher.appendAndOutbox(assetId, "PhysicalAsset", expectedVersion, newEvents, actorRef, null,
                        commandId);
            }
            return null;
        });
    }

    /**
     * NUEVA-3 — Camino A: registra un PhysicalAsset (génesis).
     * organizationRef se recibe aquí porque la saga (NUEVA-4) lo va a pasar,
     * pero todavía no se usa en el Aggregate (eso llega en la tarea 5.3).
     */
    public void registerPhysicalAsset(String commandId,
            String organizationRef,
            String assetType,
            BigDecimal quantity,
            String unitOfMeasure,
            String custodianRef,
            String currentLocation,
            String allocationId,
            String sourceAllocationId,
            com.traceability.core.domain.event.ActorRef actorRef) {

        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            authorize(actorRef, organizationRef);

            String assetId = UUID.randomUUID().toString();

            PhysicalAsset asset = PhysicalAsset.register(
                    assetId,
                    assetType,
                    quantity,
                    unitOfMeasure,
                    currentLocation,
                    custodianRef,
                    null, // parentAssetRef
                    assetId, // rootAssetRef (él mismo al nacer)
                    allocationId,
                    sourceAllocationId,
                    organizationRef,
                    null // donorRef: null en Camino A
            );

            List<DomainEvent> newEvents = asset.getUncommittedEvents();

            eventPublisher.appendAndOutbox(
                    assetId,
                    "PhysicalAsset",
                    0, // génesis → expectedVersion = 0
                    newEvents,
                    actorRef,
                    List.of(),
                    commandId);
            return null;
        });
    }

    /**
     * NUEVA-3 — Divide un PhysicalAsset existente.
     */
    public void splitPhysicalAsset(String commandId,
            String assetId,
            BigDecimal splitQuantity,
            com.traceability.core.domain.event.ActorRef actorRef) {

        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(assetId);
            List<DomainEventPayload> payloads = events.stream()
                    .map(DomainEvent::payload)
                    .collect(Collectors.toList());

            PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, payloads, events.size());
            long expectedVersion = asset.getVersion();

            authorize(actorRef, asset.getOrganizationRef());

            String childAssetId = UUID.randomUUID().toString();

            asset.split(childAssetId, splitQuantity);

            List<DomainEvent> newEvents = asset.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(
                        assetId,
                        "PhysicalAsset",
                        expectedVersion,
                        newEvents,
                        actorRef,
                        null,
                        commandId);
            }
            return null;
        });
    }
}
