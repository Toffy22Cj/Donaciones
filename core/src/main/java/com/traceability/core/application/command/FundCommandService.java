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
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FundCommandService {

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;
    private final RoleAuthorizationPolicy roleAuthorizationPolicy;
    private final OrganizationBoundaryPolicy organizationBoundaryPolicy;
    private final IdentityPrincipalPort identityPrincipalPort;

    public FundCommandService(CommandRetryTemplate retryTemplate,
                              ProcessedCommandRepositoryPort processedCommandRepository,
                              EventStorePort eventStore,
                              TransactionalEventPublisher eventPublisher,
                              RoleAuthorizationPolicy roleAuthorizationPolicy,
                              OrganizationBoundaryPolicy organizationBoundaryPolicy,
                              IdentityPrincipalPort identityPrincipalPort) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
        this.roleAuthorizationPolicy = roleAuthorizationPolicy;
        this.organizationBoundaryPolicy = organizationBoundaryPolicy;
        this.identityPrincipalPort = identityPrincipalPort;
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

    public void registerFund(String commandId, String fundId, OrganizationRef organizationRef, String campaignRef, String donorRef, String currency, Long pledgedAmount, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            authorize(actorRef, organizationRef != null ? organizationRef.value() : null, CommandType.REGISTER_FUND);

            Fund fund = Fund.registerFund(fundId, organizationRef, pledgedAmount, currency, campaignRef, donorRef);
            List<DomainEvent> newEvents = fund.getUncommittedEvents();

            eventPublisher.appendAndOutbox(fundId, "Fund", 0, newEvents, actorRef, java.util.List.of(), commandId);
            return null;
        });
    }

    public void clearFundsGenesis(String commandId, String fundId, OrganizationRef organizationRef, String campaignRef, String donorRef, String currency, long amount, String sourceRef, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            appendGenesis(commandId, fundId, organizationRef, campaignRef, donorRef, currency, amount, sourceRef, actorRef);
            return null;
        });
    }

    /**
     * T1 — Génesis de {@code Fund} para el orquestador de aplicación de fondos de {@code app} (ADR-045, Tx 2).
     * <p>
     * Exige una transacción ya abierta ({@link Propagation#MANDATORY}) y se une a ella. <b>Sin reintento interno</b>:
     * un conflicto o un error transitorio de MongoDB aborta la transacción externa, así que reintentar aquí sería
     * reintentar sobre una transacción ya abortada. Las excepciones se propagan tal cual, con su causa, para que el
     * orquestador reintente la transacción completa (ADR-037 §2.3, E1 §6). Sin mensaje de outbox (D-P8, opción A).
     *
     * @return {@code true} si escribió la génesis; {@code false} si el {@code commandId} ya estaba reclamado
     *         (no-op idempotente).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean clearFundsGenesisWithinTransaction(String commandId, String fundId, OrganizationRef organizationRef, String campaignRef, String donorRef, String currency, long amount, String sourceRef, com.traceability.core.domain.event.ActorRef actorRef) {
        return appendGenesis(commandId, fundId, organizationRef, campaignRef, donorRef, currency, amount, sourceRef, actorRef);
    }

    private boolean appendGenesis(String commandId, String fundId, OrganizationRef organizationRef, String campaignRef, String donorRef, String currency, long amount, String sourceRef, com.traceability.core.domain.event.ActorRef actorRef) {
        authorize(actorRef, organizationRef != null ? organizationRef.value() : null, CommandType.CLEAR_FUNDS_AS_GENESIS);

        Fund fund = Fund.clearFundsGenesis(fundId, organizationRef, amount, sourceRef, currency, campaignRef, donorRef);
        List<DomainEvent> newEvents = fund.getUncommittedEvents();

        return eventPublisher.appendAndOutbox(fundId, "Fund", 0, newEvents, actorRef, java.util.List.of(), commandId);
    }

    public void clearFundsForPledge(String commandId, String fundId, long amount, String sourceRef, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

            authorize(actorRef, fund.getOrganizationRef().value(), CommandType.CLEAR_FUNDS_FOR_PLEDGE);

            fund.clearFunds(amount, sourceRef);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId);
            return null;
        });
    }

    /**
     * Pide una asignación. Un reenvío del mismo {@code commandId} con la misma asignación es un no-op; con otra, un
     * {@link com.traceability.core.application.exception.CommandIdReusedException} (plan P1.1, como DD-11). Un fondo
     * inexistente es {@link com.traceability.core.application.exception.FundNotFoundException} (DD-30).
     */
    public void requestAllocation(String commandId, String fundId, String allocationId, long amount, com.traceability.core.domain.event.ActorRef actorRef) {
        String outcome = "REQUEST_ALLOCATION:" + fundId + ":" + allocationId;
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return;
        }

        boolean written = retryTemplate.execute(() -> {
            Fund fund = loadExisting(fundId);
            long expectedVersion = fund.getVersion();

            authorize(actorRef, fund.getOrganizationRef() != null ? fund.getOrganizationRef().value() : null, CommandType.REQUEST_ALLOCATION);

            fund.requestAllocation(allocationId, amount);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            return eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId, outcome);
        });
        if (!written) {
            assertSameCommand(commandId, outcome);
        }
    }

    /** El reclamo existente es de este mismo comando, o el {@code commandId} se reutilizó (plan P1.1, como DD-11). */
    private void assertSameCommand(String commandId, String expectedOutcome) {
        String actual = processedCommandRepository.findOutcome(commandId).orElse(null);
        if (!expectedOutcome.equals(actual)) {
            throw new com.traceability.core.application.exception.CommandIdReusedException(commandId);
        }
    }

    private Fund loadExisting(String fundId) {
        List<DomainEvent> events = eventStore.loadStream(fundId);
        if (events.isEmpty()) {
            throw new com.traceability.core.application.exception.FundNotFoundException(fundId);
        }
        return Fund.rehydrate(fundId, events.stream().map(DomainEvent::payload).collect(Collectors.toList()), events.size());
    }

    /**
     * Confirmación manual de una asignación por HTTP (plan P1.1, DD-32): reclamo con resultado, como
     * {@link #requestAllocation}. La saga del registro usa {@link #confirmAllocation}, sin cambios.
     */
    public void confirmAllocationByCommand(String commandId, String fundId, String allocationId,
                                           com.traceability.core.domain.event.ActorRef actorRef) {
        String outcome = "CONFIRM_ALLOCATION:" + fundId + ":" + allocationId;
        if (processedCommandRepository.exists(commandId)) {
            assertSameCommand(commandId, outcome);
            return;
        }
        boolean written = retryTemplate.execute(() -> {
            Fund fund = loadExisting(fundId);
            long expectedVersion = fund.getVersion();
            authorize(actorRef, fund.getOrganizationRef() != null ? fund.getOrganizationRef().value() : null, CommandType.CONFIRM_ALLOCATION);
            fund.confirmAllocation(allocationId);
            return eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, fund.getUncommittedEvents(), actorRef,
                    null, commandId, outcome);
        });
        if (!written) {
            assertSameCommand(commandId, outcome);
        }
    }

    public void confirmAllocation(String commandId, String fundId, String allocationId, com.traceability.core.domain.event.ActorRef actorRef) {

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

            authorize(actorRef, fund.getOrganizationRef() != null ? fund.getOrganizationRef().value() : null, CommandType.CONFIRM_ALLOCATION);

            fund.confirmAllocation(allocationId);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId);
            } else {
                // If there are no new events, we still need to claim the command to prevent infinite retries from saga.
                // We do this by calling appendAndOutbox with empty events list.
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, java.util.Collections.emptyList(), actorRef, null, commandId);
            }
            return null;
        });
    }

    // Internal saga compensation only (AssetRegisteredSagaPolicy, SystemActor): intentionally no authorize().
    // Human-initiated reversals must use reverseAllocationAdministratively (ADR-036 §2-3).
    public void reverseAllocation(String commandId, String fundId, String allocationId, String reason, com.traceability.core.domain.event.ActorRef actorRef) {

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

            fund.reverseAllocation(allocationId, reason);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId);
            } else {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, java.util.Collections.emptyList(), actorRef, null, commandId);
            }
            return null;
        });
    }

    public void reverseAllocationAdministratively(String commandId, String fundId, String allocationId, String reason, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

            authorize(actorRef, fund.getOrganizationRef() != null ? fund.getOrganizationRef().value() : null, CommandType.REVERSE_ALLOCATION_ADMINISTRATIVELY);

            fund.reverseAllocation(allocationId, reason);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId);
            } else {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, java.util.Collections.emptyList(), actorRef, null, commandId);
            }
            return null;
        });
    }
}
