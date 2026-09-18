package com.traceability.core.application.command;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FundCommandService {

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;

    public FundCommandService(CommandRetryTemplate retryTemplate,
                              ProcessedCommandRepositoryPort processedCommandRepository,
                              EventStorePort eventStore,
                              TransactionalEventPublisher eventPublisher) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
    }

    public void registerFund(String commandId, String fundId, OrganizationRef organizationRef, String campaignRef, String donorRef, String currency, Long pledgedAmount, com.traceability.core.domain.event.ActorRef actorRef) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
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
            Fund fund = Fund.clearFundsGenesis(fundId, organizationRef, amount, sourceRef, currency, campaignRef, donorRef);
            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            
            eventPublisher.appendAndOutbox(fundId, "Fund", 0, newEvents, actorRef, java.util.List.of(), commandId);
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
