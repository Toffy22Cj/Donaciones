package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.port.out.SagaManualActionLogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Salida manual de la cuarentena de sagas (ADR-007/008 Enmienda 1, D5). Cada operación es condicional (solo sobre
 * {@code QUARANTINED}) y escribe, en la misma transacción que el cambio de estado, un registro de auditoría con
 * operador, nota y fecha. La resolución manual llama antes a {@link SagaPolicy#onManualResolution}, que puede fijar el
 * estado de su dominio (la división toma su reclamo) o rechazarla.
 */
@Service
public class SagaManualResolutionService {

    private static final Logger log = LoggerFactory.getLogger(SagaManualResolutionService.class);

    private final OutboxPort outboxPort;
    private final SagaManualActionLogPort actionLog;
    private final Map<String, SagaPolicy> policies;
    private final Clock clock;

    public SagaManualResolutionService(OutboxPort outboxPort, SagaManualActionLogPort actionLog, List<SagaPolicy> policies,
                                       ObjectProvider<Clock> clock) {
        this.outboxPort = outboxPort;
        this.actionLog = actionLog;
        this.policies = policies.stream().collect(Collectors.toMap(SagaPolicy::getSagaType, Function.identity()));
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public void retryResolution(String messageId, String operator, String note) {
        OutboxMessage message = quarantined(messageId);
        Instant now = clock.instant();
        OutboxMessage retried = message.withState(OutboxStatus.PENDING, message.retryCount(), now, now,
                message.lastFailureReason());
        apply(retried, "RETRY_RESOLUTION", operator, note, now);
    }

    @Transactional
    public void markResolvedManually(String messageId, String operator, String note) {
        OutboxMessage message = quarantined(messageId);
        SagaPolicy policy = policies.get(message.sagaType());
        if (policy != null) {
            policy.onManualResolution(message, operator, note);
        }
        Instant now = clock.instant();
        apply(message.withManualResolution(operator, note, now), "MARK_RESOLVED_MANUALLY", operator, note, now);
    }

    private OutboxMessage quarantined(String messageId) {
        OutboxMessage message = outboxPort.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown saga message " + messageId));
        if (message.status() != OutboxStatus.QUARANTINED) {
            throw new IllegalStateException("Saga message " + messageId + " is " + message.status() + ", not QUARANTINED");
        }
        return message;
    }

    private void apply(OutboxMessage updated, String action, String operator, String note, Instant now) {
        if (!outboxPort.updateIfStatus(updated, OutboxStatus.QUARANTINED)) {
            throw new IllegalStateException("Saga message " + updated.messageId() + " is no longer QUARANTINED");
        }
        actionLog.record(updated.messageId(), updated.sagaType(), updated.correlationId(), action,
                OutboxStatus.QUARANTINED.name(), operator, note, now);
        log.warn("Saga message {} ({}): {} by {}: {}", updated.messageId(), updated.sagaType(), action, operator, note);
    }
}
