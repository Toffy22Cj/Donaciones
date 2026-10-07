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
    private java.time.Clock clock = java.time.Clock.systemUTC();

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

    /** Con reloj inyectable: la fecha de los mensajes de saga es la del coordinador (ADR-007/008 Enmienda 1). */
    @org.springframework.beans.factory.annotation.Autowired
    public PhysicalAssetCommandService(CommandRetryTemplate retryTemplate,
            ProcessedCommandRepositoryPort processedCommandRepository,
            EventStorePort eventStore,
            TransactionalEventPublisher eventPublisher,
            RoleAuthorizationPolicy roleAuthorizationPolicy,
            OrganizationBoundaryPolicy organizationBoundaryPolicy,
            IdentityPrincipalPort identityPrincipalPort,
            CampaignInKindEligibilityPort campaignInKindEligibility,
            org.springframework.beans.factory.ObjectProvider<java.time.Clock> clock) {
        this(retryTemplate, processedCommandRepository, eventStore, eventPublisher, roleAuthorizationPolicy,
                organizationBoundaryPolicy, identityPrincipalPort, campaignInKindEligibility);
        this.clock = clock.getIfAvailable(java.time.Clock::systemUTC);
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
        transition(commandId, assetId, CommandType.DELIVER_ASSET, actorRef,
                asset -> asset.deliver(finalCustodianRef, beneficiaryRef, locationRef, evidenceRef, deliveredAt));
    }

    /** D-ASSET (plan B6-c §2.1): despacho, con el mismo patrón que {@link #deliverAsset}. */
    public void dispatchAsset(String commandId, String assetId, String carrierRef,
            com.traceability.core.domain.event.ActorRef actorRef) {
        transition(commandId, assetId, CommandType.DISPATCH_PHYSICAL_ASSET, actorRef, asset -> asset.dispatch(carrierRef));
    }

    /** D-ASSET (plan B6-c §2.1): recepción en una instalación. */
    public void receiveAsset(String commandId, String assetId, String facilityLocation, String receiverRef,
            com.traceability.core.domain.event.ActorRef actorRef) {
        transition(commandId, assetId, CommandType.RECEIVE_PHYSICAL_ASSET, actorRef,
                asset -> asset.receive(facilityLocation, receiverRef));
    }

    /**
     * Transición de un activo existente bajo el reclamo {@code commandId}, que guarda el resultado
     * {@code TIPO:assetId} (DD-11): un reenvío del mismo comando es un no-op; el de otro comando, un
     * {@link com.traceability.core.application.exception.CommandIdReusedException}.
     */
    private void transition(String commandId, String assetId, CommandType commandType,
            com.traceability.core.domain.event.ActorRef actorRef, java.util.function.Consumer<PhysicalAsset> change) {
        String outcome = outcome(commandType, assetId);
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return;
        }
        boolean written = retryTemplate.execute(() -> {
            PhysicalAsset asset = loadExisting(assetId);
            long expectedVersion = asset.getVersion();
            authorize(actorRef, asset.getOrganizationRef(), commandType);
            change.accept(asset);
            return eventPublisher.appendAndOutbox(assetId, "PhysicalAsset", expectedVersion,
                    asset.getUncommittedEvents(), actorRef, null, commandId, outcome);
        });
        if (!written) {
            assertSameCommand(commandId, outcome);
        }
    }

    private static String outcome(CommandType commandType, String assetId) {
        return commandType.name() + ":" + assetId;
    }

    /** El reclamo existente es de este mismo comando, o el {@code commandId} se reutilizó (DD-11). */
    private void assertSameCommand(String commandId, String expectedOutcome) {
        String actual = processedCommandRepository.findOutcome(commandId).orElse(null);
        if (!expectedOutcome.equals(actual)) {
            throw new com.traceability.core.application.exception.CommandIdReusedException(commandId);
        }
    }

    /** Carga un activo que debe existir (DD-12). */
    private PhysicalAsset loadExisting(String assetId) {
        List<DomainEvent> events = eventStore.loadStream(assetId);
        if (events.isEmpty()) {
            throw new com.traceability.core.application.exception.PhysicalAssetNotFoundException(assetId);
        }
        return PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).collect(Collectors.toList()),
                events.size());
    }

    /** Resultado de un registro, leído del activo ya escrito (el original si es un duplicado). */
    private RegisteredAsset registered(String assetId) {
        PhysicalAsset asset = loadExisting(assetId);
        return new RegisteredAsset(assetId, asset.getDonationRef(), asset.getCampaignRef());
    }

    /**
     * NUEVA-3 — Camino A: registra un PhysicalAsset (génesis) contra la asignación de un Fund.
     * organizationRef lo aporta el llamador (rectificación NUEVA-3), pero ADR-029 exige que sea el
     * del Fund: como la saga que iba a resolverlo fue descartada (ADR-034), aquí se valida contra el
     * Fund cargado y se rechaza cualquier discrepancia antes de autorizar o persistir.
     */
    public RegisteredAsset registerPhysicalAsset(String commandId,
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

        String assetId = com.traceability.core.domain.physicalasset.AssetIds.of(organizationRef, commandId);
        String outcome = outcome(CommandType.REGISTER_PHYSICAL_ASSET, assetId);
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return registered(assetId);
        }

        if (fundId == null || fundId.isBlank()) {
            throw new com.traceability.core.application.exception.InvalidFundReferenceException("fundId cannot be null or empty for Asset Registration (Path A)");
        }

        RegisteredAsset result = retryTemplate.execute(() -> {
            Fund fund = assertOrganizationMatchesFund(fundId, organizationRef);
            authorize(actorRef, organizationRef, CommandType.REGISTER_PHYSICAL_ASSET);
            // ADR-029 Enmienda 1, D2: el campaignRef es el del Fund, nunca del llamador. Decisión escrita
            // (plan-d-campaign.md §3.2): no se comprueba que la convocatoria siga OPEN; gastar fondos ya
            // recaudados tras el cierre es legítimo (ADR-037 §2.6bis, D1).
            String campaignRef = fund.getCampaignRef();

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
                    clock.instant(),
                    clock.instant()
            );

            boolean written = eventPublisher.appendAndOutbox(
                    assetId,
                    "PhysicalAsset",
                    0, // génesis → expectedVersion = 0
                    newEvents,
                    actorRef,
                    List.of(sagaMessage),
                    commandId,
                    outcome);
            return written ? new RegisteredAsset(assetId, asset.getDonationRef(), asset.getCampaignRef()) : null;
        });
        if (result != null) {
            return result;
        }
        // otro llamador ganó el reclamo en paralelo: es este mismo comando o uno distinto (DD-11)
        assertSameCommand(commandId, outcome);
        return registered(assetId);
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
    public RegisteredAsset registerPhysicalAssetFromDonation(String commandId,
            String organizationRef,
            String donorRef,
            String assetType,
            BigDecimal quantity,
            String unitOfMeasure,
            String custodianRef,
            String currentLocation,
            com.traceability.core.domain.event.ActorRef actorRef) {
        return registerPhysicalAssetFromDonation(commandId, organizationRef, donorRef, assetType, quantity, unitOfMeasure,
                custodianRef, currentLocation, null, actorRef);
    }

    /**
     * Tarea 5.4 — Camino B: registra un PhysicalAsset directamente por donación en especie, con convocatoria
     * opcional (ADR-029 Enmienda 1, D3). Orden: autorizar → validar el {@code campaignRef} con
     * {@link CampaignInKindEligibilityPort} → persistir. Autorizar primero impide que un actor sondee si un
     * {@code campaignRef} existe en otra organización; un rechazo no deja eventos ni reclamo del {@code commandId}.
     */
    public RegisteredAsset registerPhysicalAssetFromDonation(String commandId,
            String organizationRef,
            String donorRef,
            String assetType,
            BigDecimal quantity,
            String unitOfMeasure,
            String custodianRef,
            String currentLocation,
            String campaignRef,
            com.traceability.core.domain.event.ActorRef actorRef) {

        String assetId = com.traceability.core.domain.physicalasset.AssetIds.of(organizationRef, commandId);
        String outcome = outcome(CommandType.REGISTER_PHYSICAL_ASSET_FROM_DONATION, assetId);
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return registered(assetId);
        }

        String donationRef = UUID.randomUUID().toString();

        RegisteredAsset result = retryTemplate.execute(() -> {
            authorize(actorRef, organizationRef, CommandType.REGISTER_PHYSICAL_ASSET_FROM_DONATION);
            if (campaignRef != null) {
                assertCampaignAcceptsInKind(campaignRef, organizationRef);
            }

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

            boolean written = eventPublisher.appendAndOutbox(
                    assetId,
                    "PhysicalAsset",
                    0, // génesis → expectedVersion = 0
                    newEvents,
                    actorRef,
                    List.of(),
                    commandId,
                    outcome);
            return written ? new RegisteredAsset(assetId, asset.getDonationRef(), asset.getCampaignRef()) : null;
        });
        if (result != null) {
            return result;
        }
        // otro llamador ganó el reclamo en paralelo: es este mismo comando o uno distinto (DD-11)
        assertSameCommand(commandId, outcome);
        return registered(assetId);
    }

    /**
     * NUEVA-3 — Divide un PhysicalAsset existente (D-SPLIT S1 y S2; B1-bis).
     *
     * <p>El hijo nace de forma asíncrona: en la misma transacción que {@code ASSET_SPLIT} se escribe el mensaje de la
     * saga {@code ASSET_SPLIT_SAGA}, que creará el hijo ({@link #createSplitChild}). El id del hijo es determinista
     * ({@code SplitChildIds.of(assetId, commandId)}) y se calcula fuera del reintento: repetir el comando devuelve el
     * mismo hijo sin guardar nada.
     *
     * @return el {@code childAssetId}
     */
    public String splitPhysicalAsset(String commandId,
            String assetId,
            BigDecimal splitQuantity,
            com.traceability.core.domain.event.ActorRef actorRef) {

        String childAssetId = com.traceability.core.domain.physicalasset.SplitChildIds.of(assetId, commandId);
        String outcome = outcome(CommandType.SPLIT_PHYSICAL_ASSET, childAssetId);
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return childAssetId;
        }

        boolean written = retryTemplate.execute(() -> {
            PhysicalAsset asset = loadExisting(assetId);
            long expectedVersion = asset.getVersion();

            authorize(actorRef, asset.getOrganizationRef(), CommandType.SPLIT_PHYSICAL_ASSET);

            asset.split(childAssetId, splitQuantity);

            Instant now = clock.instant();
            com.traceability.core.application.saga.OutboxMessage sagaMessage = new com.traceability.core.application.saga.OutboxMessage(
                    UUID.randomUUID().toString(),
                    com.traceability.core.application.saga.SplitPhysicalAssetSagaPolicy.SAGA_TYPE,
                    assetId,
                    childAssetId,
                    String.format("{\"parentAssetId\":\"%s\",\"childAssetId\":\"%s\"}", assetId, childAssetId),
                    com.traceability.core.application.saga.OutboxStatus.PENDING,
                    0,
                    now,
                    now);

            return eventPublisher.appendAndOutbox(
                    assetId,
                    "PhysicalAsset",
                    expectedVersion,
                    asset.getUncommittedEvents(),
                    actorRef,
                    List.of(sagaMessage),
                    commandId,
                    outcome);
        });
        if (!written) {
            assertSameCommand(commandId, outcome);
        }
        return childAssetId;
    }

    /**
     * Rama "crear el hijo" de la saga de la división, bajo la barrera {@code SPLIT_RESOLUTION:{childAssetId}} (plan
     * B1-bis §2): el reclamo y la génesis del hijo se confirman en la misma transacción. Si el reclamo ya existe, no
     * escribe nada y devuelve el resultado que lo ganó.
     *
     * @throws IllegalArgumentException si el padre no tiene un {@code ASSET_SPLIT} con ese hijo (fallo permanente)
     */
    public com.traceability.core.application.saga.SplitResolution createSplitChild(String parentAssetId, String childAssetId) {
        String claimKey = com.traceability.core.application.saga.SplitResolution.claimKey(childAssetId);
        return retryTemplate.execute(() -> {
            java.util.Optional<com.traceability.core.application.saga.SplitResolution> resolved = resolution(claimKey);
            if (resolved.isPresent()) {
                return resolved.get();
            }
            PhysicalAsset parent = load(parentAssetId);
            PhysicalAsset child = PhysicalAsset.registerSplitChild(parent, childAssetId);
            boolean written = eventPublisher.appendAndOutbox(childAssetId, "PhysicalAsset", 0,
                    child.getUncommittedEvents(), SPLIT_SAGA_ACTOR, null, claimKey,
                    com.traceability.core.application.saga.SplitResolution.CHILD_CREATED.name());
            return written ? com.traceability.core.application.saga.SplitResolution.CHILD_CREATED : resolution(claimKey).orElseThrow();
        });
    }

    /**
     * Rama "compensar" de la saga de la división, bajo la misma barrera que {@link #createSplitChild}: reintegra al
     * padre exactamente la cantidad extraída (D-SPLIT S4).
     *
     * @throws com.traceability.core.domain.physicalasset.exceptions.AssetTerminalStateException si el padre está
     *         {@code DELIVERED}: compensar es imposible y la saga recupera hacia delante (Enmienda 1, D4)
     */
    public com.traceability.core.application.saga.SplitResolution compensateSplitChild(String parentAssetId, String childAssetId) {
        String claimKey = com.traceability.core.application.saga.SplitResolution.claimKey(childAssetId);
        return retryTemplate.execute(() -> {
            java.util.Optional<com.traceability.core.application.saga.SplitResolution> resolved = resolution(claimKey);
            if (resolved.isPresent()) {
                return resolved.get();
            }
            PhysicalAsset parent = load(parentAssetId);
            long expectedVersion = parent.getVersion();
            BigDecimal extracted = parent.findSplit(childAssetId).orElseThrow(() -> new IllegalArgumentException(
                    "Asset " + parentAssetId + " has no split with child " + childAssetId)).extractedQuantity();
            parent.compensateSplit(childAssetId, extracted);
            boolean written = eventPublisher.appendAndOutbox(parentAssetId, "PhysicalAsset", expectedVersion,
                    parent.getUncommittedEvents(), SPLIT_SAGA_ACTOR, null, claimKey,
                    com.traceability.core.application.saga.SplitResolution.COMPENSATED.name());
            return written ? com.traceability.core.application.saga.SplitResolution.COMPENSATED : resolution(claimKey).orElseThrow();
        });
    }

    private static final com.traceability.core.domain.event.SystemActor SPLIT_SAGA_ACTOR =
            new com.traceability.core.domain.event.SystemActor("SplitPhysicalAssetSagaPolicy");

    private java.util.Optional<com.traceability.core.application.saga.SplitResolution> resolution(String claimKey) {
        return processedCommandRepository.findOutcome(claimKey)
                .map(com.traceability.core.application.saga.SplitResolution::valueOf);
    }

    private PhysicalAsset load(String assetId) {
        List<DomainEvent> events = eventStore.loadStream(assetId);
        return PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).collect(Collectors.toList()),
                events.size());
    }
}
