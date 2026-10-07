package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic Saga Coordinator using the Transactional Outbox pattern.
 * Ref: ADR-007, ADR-008, ADR-009, ADR-010; ADR-007/008 Enmienda 1.
 *
 * <ol>
 *   <li><b>Ejecución</b>, hasta {@code createdAt + saga.quarantine.window} (4 h): {@code execute}; un fallo transitorio
 *       reintenta con <i>backoff</i> {@code 2^n × 15 s}; uno permanente pasa a resolución en el acto.</li>
 *   <li><b>Resolución</b>, hasta {@code resolutionStartedAt + saga.resolution.window} (4 h): {@code compensate}; éxito
 *       → {@code RESOLVED}; fallo transitorio → reintento con tope de 1 h; fallo permanente o ventana agotada →
 *       {@code QUARANTINED} con log ERROR (necesita a una persona: {@link SagaOutboxAdministration}).</li>
 * </ol>
 */
@Component
public class OutboxSagaCoordinator {

    public static final String DEFAULT_EXECUTION_WINDOW = "PT4H";
    public static final String DEFAULT_RESOLUTION_WINDOW = "PT4H";
    static final Duration MAX_RESOLUTION_BACKOFF = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(OutboxSagaCoordinator.class);

    private final OutboxPort outboxPort;
    private final Map<String, SagaPolicy> policies;
    private final Duration executionWindow;
    private final Duration resolutionWindow;
    private final Clock clock;

    @Autowired
    public OutboxSagaCoordinator(OutboxPort outboxPort,
                                 List<SagaPolicy> policyList,
                                 @Value("${saga.quarantine.window:" + DEFAULT_EXECUTION_WINDOW + "}") Duration executionWindow,
                                 @Value("${saga.resolution.window:" + DEFAULT_RESOLUTION_WINDOW + "}") Duration resolutionWindow,
                                 ObjectProvider<Clock> clock) {
        this(outboxPort, policyList, executionWindow, resolutionWindow, clock.getIfAvailable(Clock::systemUTC));
    }

    public OutboxSagaCoordinator(OutboxPort outboxPort, List<SagaPolicy> policyList, Duration executionWindow,
                                 Duration resolutionWindow, Clock clock) {
        this.outboxPort = outboxPort;
        this.executionWindow = executionWindow;
        this.resolutionWindow = resolutionWindow;
        this.clock = clock;
        this.policies = policyList.stream()
            .collect(Collectors.toMap(SagaPolicy::getSagaType, Function.identity()));
    }

    public Duration executionWindow() {
        return executionWindow;
    }

    public Duration resolutionWindow() {
        return resolutionWindow;
    }

    @Scheduled(fixedDelayString = "${saga.outbox.delay:500}")
    public void processPendingMessages() {
        Instant now = clock.instant();
        for (OutboxMessage message : outboxPort.fetchPendingMessages(now)) {
            SagaPolicy policy = policies.get(message.sagaType());
            if (policy == null) {
                continue; // sin política para este tipo de saga
            }
            if (message.inResolution()) {
                resolve(message, policy, now);
            } else if (now.isAfter(message.createdAt().plus(executionWindow))) {
                resolve(startResolution(message, now, "execution window expired"), policy, now);
            } else {
                execute(message, policy, now);
            }
        }
    }

    private void execute(OutboxMessage message, SagaPolicy policy, Instant now) {
        try {
            policy.execute(message);
            outboxPort.update(message.withState(OutboxStatus.COMPLETED, message.retryCount(), message.nextRetryAt(),
                    null, message.lastFailureReason()));
        } catch (PermanentSagaFailureException e) {
            resolve(startResolution(message, now, reason(e)), policy, now);
        } catch (Exception e) {
            // Backoff exponencial: 2^retryCount * 15s
            long delaySeconds = (long) Math.pow(2, Math.min(message.retryCount(), 20)) * 15L;
            outboxPort.update(message.withState(OutboxStatus.PENDING, message.retryCount() + 1,
                    now.plusSeconds(delaySeconds), null, reason(e)));
        }
    }

    private void resolve(OutboxMessage message, SagaPolicy policy, Instant now) {
        try {
            policy.compensate(message);
            outboxPort.update(message.withState(OutboxStatus.RESOLVED, message.retryCount(), message.nextRetryAt(),
                    message.resolutionStartedAt(), message.lastFailureReason()));
        } catch (PermanentSagaFailureException e) {
            quarantine(message, reason(e));
        } catch (Exception e) {
            if (now.isAfter(message.resolutionStartedAt().plus(resolutionWindow))) {
                quarantine(message, reason(e));
                return;
            }
            Duration delay = Duration.ofSeconds((long) Math.pow(2, Math.min(message.retryCount(), 20)) * 15L);
            if (delay.compareTo(MAX_RESOLUTION_BACKOFF) > 0) {
                delay = MAX_RESOLUTION_BACKOFF;
            }
            outboxPort.update(message.withState(OutboxStatus.PENDING, message.retryCount() + 1, now.plus(delay),
                    message.resolutionStartedAt(), reason(e)));
        }
    }

    private static OutboxMessage startResolution(OutboxMessage message, Instant now, String reason) {
        return message.withState(OutboxStatus.PENDING, message.retryCount(), now, now, reason);
    }

    private void quarantine(OutboxMessage message, String reason) {
        log.error("Saga message {} ({}, correlation {}) QUARANTINED: needs an operator (SagaOutboxAdministration). Reason: {}",
                message.messageId(), message.sagaType(), message.correlationId(), reason);
        outboxPort.update(message.withState(OutboxStatus.QUARANTINED, message.retryCount(), message.nextRetryAt(),
                message.resolutionStartedAt(), reason));
    }

    private static String reason(Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
