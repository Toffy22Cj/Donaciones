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
            authorize(actorRef, organizationRef != null ? organizationRef.value() : null, CommandType.CLEAR_FUNDS_AS_GENESIS);

            Fund fund = Fund.clearFundsGenesis(fundId, organizationRef, amount, sourceRef, currency, campaignRef, donorRef);
            List<DomainEvent> newEvents = fund.getUncommittedEvents();

            eventPublisher.appendAndOutbox(fundId, "Fund", 0, newEvents, actorRef, java.util.List.of(), commandId);
            return null;
        });
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

    public void requestAllocation(String commandId, String fundId, String allocationId, long amount, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

            fund.requestAllocation(allocationId, amount);

            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, actorRef, null, commandId);
            return null;
        });
    }

    public void confirmAllocation(String commandId, String fundId, String allocationId, com.traceability.core.domain.event.ActorRef actorRef) {

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();

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
}
