package com.traceability.core.application.command;

import com.traceability.core.application.authorization.CrossOrganizationAccessException;
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
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.campaign.CampaignInKindEligibilityPort;
import com.traceability.contracts.campaign.InKindEligibility;
import com.traceability.core.application.exception.InKindCampaignClosedException;
import com.traceability.core.application.exception.InKindCampaignNotFoundException;
import com.traceability.core.application.exception.InKindCampaignOfOtherOrganizationException;
import com.traceability.core.application.exception.InKindNotAcceptedByCampaignException;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PhysicalAssetCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PhysicalAssetCommandService.class);

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;
    private final RoleAuthorizationPolicy roleAuthorizationPolicy;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;
    private final IdentityPrincipalPort identityPrincipalPort;
    private final CampaignInKindEligibilityPort campaignInKindEligibility;

    public PhysicalAssetCommandService(CommandRetryTemplate retryTemplate,
            ProcessedCommandRepositoryPort processedCommandRepository,
            EventStorePort eventStore,
            TransactionalEventPublisher eventPublisher,
            RoleAuthorizationPolicy roleAuthorizationPolicy,
            OrganizationBoundaryPolicy organizationBoundaryPolicy,
            IdentityPrincipalPort identityPrincipalPort,
            CampaignInKindEligibilityPort campaignInKindEligibility) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
        this.roleAuthorizationPolicy = roleAuthorizationPolicy;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
        this.identityPrincipalPort = identityPrincipalPort;
        this.campaignInKindEligibility = campaignInKindEligibility;
    }

    private void authorize(ActorRef actorRef, String organizationRef, CommandType commandType) {
        switch (actorRef) {
            case SystemActor sa -> {
                // bypass P7/P9
            }
            case ExternalActor ea -> {
                // bypass P7/P9
            }
            case HumanActor ha -> {
                AuthorizationPrincipal principal = identityPrincipalPort.resolvePrincipal(ha.accountId());
                organizationBoundaryPolicy.assertBelongs(principal.organizationId(), organizationRef);
                roleAuthorizationPolicy.authorize(principal, commandType);
            }
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

            authorize(actorRef, asset.getOrganizationRef(), CommandType.DELIVER_ASSET);

            asset.deliver(finalCustodianRef, beneficiaryRef, locationRef, evidenceRef, deliveredAt);

            List<DomainEvent> newEvents = asset.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(assetId, "PhysicalAsset", expectedVersion, newEvents, actorRef, null,
                        commandId);
            } else {
                eventPublisher.appendAndOutbox(assetId, "PhysicalAsset", expectedVersion, java.util.Collections.emptyList(), actorRef, null, commandId);
            }
            return null;
        });
    }

    /**
     * NUEVA-3 — Camino A: registra un PhysicalAsset (génesis) contra la asignación de un Fund.
     * organizationRef lo aporta el llamador (rectificación NUEVA-3), pero ADR-029 exige que sea el
     * del Fund: como la saga que iba a resolverlo fue descartada (ADR-034), aquí se valida contra el
     * Fund cargado y se rechaza cualquier discrepancia antes de autorizar o persistir.
     */
    public void registerPhysicalAsset(String commandId,
            String fundId,
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

        if (fundId == null || fundId.isBlank()) {
            throw new com.traceability.core.application.exception.InvalidFundReferenceException("fundId cannot be null or empty for Asset Registration (Path A)");
        }

        retryTemplate.execute(() -> {
            Fund fund = assertOrganizationMatchesFund(fundId, organizationRef);
            authorize(actorRef, organizationRef, CommandType.REGISTER_PHYSICAL_ASSET);
            // ADR-029 Enmienda 1, D2: el campaignRef es el del Fund, nunca del llamador. Decisión escrita
            // (plan-d-campaign.md §3.2): no se comprueba que la convocatoria siga OPEN; gastar fondos ya
            // recaudados tras el cierre es legítimo (ADR-037 §2.6bis, D1).
            String campaignRef = fund.getCampaignRef();

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
                    null, // donorRef: null en Camino A
                    campaignRef
            );

            List<DomainEvent> newEvents = asset.getUncommittedEvents();

            String payloadJson = String.format("{\"allocationId\":\"%s\",\"fundId\":\"%s\"}", allocationId, fundId);
            com.traceability.core.application.saga.OutboxMessage sagaMessage = new com.traceability.core.application.saga.OutboxMessage(
                    UUID.randomUUID().toString(),
                    "ASSET_REGISTRATION_SAGA",
                    assetId,
                    fundId,
                    payloadJson,
                    com.traceability.core.application.saga.OutboxStatus.PENDING,
                    0,
                    Instant.now(),
                    Instant.now()
            );

            eventPublisher.appendAndOutbox(
                    assetId,
                    "PhysicalAsset",
                    0, // génesis → expectedVersion = 0
                    newEvents,
                    actorRef,
                    List.of(sagaMessage),
                    commandId);
            return null;
        });
    }

    /**
     * Rechaza un {@code campaignRef} que no admite esta donación en especie, con su excepción nombrada. El motivo real
     * va al log interno; hacia fuera, "no existe" y "otra organización" comparten el mismo mensaje.
     */
    private void assertCampaignAcceptsInKind(String campaignRef, String organizationRef) {
        InKindEligibility eligibility = campaignInKindEligibility.checkInKindEligibility(campaignRef, organizationRef);
        if (eligibility == InKindEligibility.ELIGIBLE) {
            return;
        }
        log.info("In-kind registration rejected: campaignRef={} organizationRef={} reason={}",
                campaignRef, organizationRef, eligibility);
        throw switch (eligibility) {
            case CAMPAIGN_NOT_FOUND -> new InKindCampaignNotFoundException(campaignRef);
            case OTHER_ORGANIZATION -> new InKindCampaignOfOtherOrganizationException(campaignRef);
            case CAMPAIGN_CLOSED -> new InKindCampaignClosedException(campaignRef);
            case IN_KIND_NOT_ACCEPTED -> new InKindNotAcceptedByCampaignException(campaignRef);
            case ELIGIBLE -> new IllegalStateException("unreachable");
        };
    }

    private Fund assertOrganizationMatchesFund(String fundId, String organizationRef) {
        List<DomainEvent> fundEvents = eventStore.loadStream(fundId);
        if (fundEvents.isEmpty()) {
            throw new com.traceability.core.application.exception.InvalidFundReferenceException("Fund " + fundId + " does not exist for Asset Registration (Path A)");
        }
        Fund fund = Fund.rehydrate(fundId, fundEvents.stream().map(DomainEvent::payload).collect(Collectors.toList()), fundEvents.size());
        String fundOrganizationRef = fund.getOrganizationRef() != null ? fund.getOrganizationRef().value() : null;
        if (fundOrganizationRef == null || !fundOrganizationRef.equals(organizationRef)) {
            throw new CrossOrganizationAccessException(
                    "organizationRef '" + organizationRef + "' does not match organizationRef '" + fundOrganizationRef + "' of Fund " + fundId + " (ADR-029, Path A)");
        }
        return fund;
    }

    /**
     * Tarea 5.4 — Camino B sin convocatoria (equivale a {@code campaignRef = null}).
     */
    public void registerPhysicalAssetFromDonation(String commandId,
            String organizationRef,
            String donorRef,
            String assetType,
            BigDecimal quantity,
            String unitOfMeasure,
            String custodianRef,
            String currentLocation,
            com.traceability.core.domain.event.ActorRef actorRef) {
        registerPhysicalAssetFromDonation(commandId, organizationRef, donorRef, assetType, quantity, unitOfMeasure,
                custodianRef, currentLocation, null, actorRef);
    }

    /**
     * Tarea 5.4 — Camino B: registra un PhysicalAsset directamente por donación en especie, con convocatoria
     * opcional (ADR-029 Enmienda 1, D3). Orden: autorizar → validar el {@code campaignRef} con
     * {@link CampaignInKindEligibilityPort} → persistir. Autorizar primero impide que un actor sondee si un
     * {@code campaignRef} existe en otra organización; un rechazo no deja eventos ni reclamo del {@code commandId}.
     */
    public void registerPhysicalAssetFromDonation(String commandId,
            String organizationRef,
            String donorRef,
            String assetType,
            BigDecimal quantity,
            String unitOfMeasure,
            String custodianRef,
            String currentLocation,
            String campaignRef,
            com.traceability.core.domain.event.ActorRef actorRef) {

        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        String donationRef = UUID.randomUUID().toString();

        retryTemplate.execute(() -> {
            authorize(actorRef, organizationRef, CommandType.REGISTER_PHYSICAL_ASSET_FROM_DONATION);
            if (campaignRef != null) {
                assertCampaignAcceptsInKind(campaignRef, organizationRef);
            }

            String assetId = UUID.randomUUID().toString();

            PhysicalAsset asset = PhysicalAsset.create(
                    assetId,
                    assetType,
                    quantity,
                    unitOfMeasure,
                    currentLocation,
                    custodianRef,
                    null, // parentAssetRef
                    assetId, // rootAssetRef (él mismo al nacer)
                    null,
                    null,
                    organizationRef,
                    donorRef,
                    donationRef,
                    campaignRef
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
    public String splitPhysicalAsset(String commandId,
            String assetId,
            BigDecimal splitQuantity,
            com.traceability.core.domain.event.ActorRef actorRef) {

        if (processedCommandRepository.exists(commandId)) {
            return null;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(assetId);
            List<DomainEventPayload> payloads = events.stream()
                    .map(DomainEvent::payload)
                    .collect(Collectors.toList());

            PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, payloads, events.size());
            long expectedVersion = asset.getVersion();

            authorize(actorRef, asset.getOrganizationRef(), CommandType.SPLIT_PHYSICAL_ASSET);

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
        return null;
    }

    /** Skeleton B1-bis. */
    public com.traceability.core.application.saga.SplitResolution createSplitChild(String parentAssetId, String childAssetId) {
        throw new UnsupportedOperationException("B1-bis: pendiente");
    }

    /** Skeleton B1-bis. */
    public com.traceability.core.application.saga.SplitResolution compensateSplitChild(String parentAssetId, String childAssetId) {
        throw new UnsupportedOperationException("B1-bis: pendiente");
    }
}
