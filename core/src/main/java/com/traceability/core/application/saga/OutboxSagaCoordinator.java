package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic Saga Coordinator using the Transactional Outbox pattern.
 * Ref: ADR-007, ADR-008, ADR-009, ADR-010
 */
public class OutboxSagaCoordinator {

    private final OutboxPort outboxPort;
    private final Map<String, SagaPolicy> policies;
    private final Duration quarantineWindow;

    public OutboxSagaCoordinator(OutboxPort outboxPort, List<SagaPolicy> policyList, Duration quarantineWindow) {
        this.outboxPort = outboxPort;
        this.quarantineWindow = quarantineWindow;
        this.policies = policyList.stream()
            .collect(Collectors.toMap(SagaPolicy::getSagaType, Function.identity()));
    }

    public void processPendingMessages() {
        Instant now = Instant.now();
        List<OutboxMessage> pendingMessages = outboxPort.fetchPendingMessages(now);

        for (OutboxMessage message : pendingMessages) {
            SagaPolicy policy = policies.get(message.sagaType());
            if (policy == null) {
                // Ignore messages for which we don't have a policy (or maybe log them)
                continue;
            }

            // 1. Evaluación de Expiración (Cuarentena Prioritaria)
            if (now.isAfter(message.createdAt().plus(quarantineWindow))) {
                try {
                    policy.compensate(message);
                } catch (Exception e) {
                    // Si falla la compensación, se captura la excepción para no romper el lote.
                    // El mensaje se marcará como QUARANTINED igualmente.
                    // Idealmente registrar en logs: log.error("Fallo de compensacion para {}", message.messageId(), e);
                }
                
                OutboxMessage quarantinedMessage = new OutboxMessage(
                    message.messageId(), message.sagaType(), message.sourceAggregateId(),
                    message.correlationId(), message.payload(), OutboxStatus.QUARANTINED,
                    message.retryCount(), message.createdAt(), message.nextRetryAt()
                );
                
                outboxPort.update(quarantinedMessage);
                continue; // NO incrementar retryCount. NO intentar execute().
            }

            // 2. Evaluación de Ejecución Normal
            try {
                policy.execute(message);
                
                OutboxMessage completedMessage = new OutboxMessage(
                    message.messageId(), message.sagaType(), message.sourceAggregateId(),
                    message.correlationId(), message.payload(), OutboxStatus.COMPLETED,
                    message.retryCount(), message.createdAt(), message.nextRetryAt()
                );
                outboxPort.update(completedMessage);
                
            } catch (Exception e) {
                int nextRetryCount = message.retryCount() + 1;
                // Backoff exponencial: 2^retryCount * 15s
                long delaySeconds = (long) Math.pow(2, message.retryCount()) * 15L;
                Instant nextRetryAt = now.plusSeconds(delaySeconds);
                
                OutboxMessage retryMessage = new OutboxMessage(
                    message.messageId(), message.sagaType(), message.sourceAggregateId(),
                    message.correlationId(), message.payload(), OutboxStatus.PENDING,
                    nextRetryCount, message.createdAt(), nextRetryAt
                );
                outboxPort.update(retryMessage);
            }
        }
    }
}
