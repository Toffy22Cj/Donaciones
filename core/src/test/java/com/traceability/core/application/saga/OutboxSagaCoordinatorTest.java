package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coordinador de sagas (ADR-007/008 Enmienda 1): fases de ejecución y de resolución, de 4 h cada una; fallo permanente
 * frente a transitorio; estados {@code RESOLVED} y {@code QUARANTINED}.
 *
 * <p>Dos casos de antes cambian de expectativa por la enmienda: compensar con éxito deja {@code RESOLVED} (antes
 * {@code QUARANTINED}), y una compensación que falla se reintenta (antes {@code QUARANTINED} sin compensar, H1).
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxSagaCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");
    private static final Duration FOUR_HOURS = Duration.ofHours(4);

    private InMemoryOutboxPort outboxPort;
    private MockSagaPolicy policy;
    private MutableClock clock;
    private OutboxSagaCoordinator coordinator;

    @BeforeEach
    void setUp() {
        outboxPort = new InMemoryOutboxPort();
        policy = new MockSagaPolicy("TEST_SAGA");
        clock = new MutableClock(T0);
        coordinator = new OutboxSagaCoordinator(outboxPort, List.of(policy), FOUR_HOURS, FOUR_HOURS, clock);
    }

    private OutboxMessage message(String id, int retryCount) {
        OutboxMessage m = new OutboxMessage(id, "TEST_SAGA", "agg-1", "corr-1", "{\"secret\":\"payload-marker\"}",
                OutboxStatus.PENDING, retryCount, T0, T0);
        outboxPort.save(m);
        return m;
    }

    private OutboxMessage stored(String id) {
        return outboxPort.findById(id).orElseThrow();
    }

    @Test
    void executeSucceeds_completed() {
        message("m", 0);

        coordinator.processPendingMessages();

        assertThat(policy.executions).isEqualTo(1);
        assertThat(policy.compensations).isZero();
        assertThat(stored("m").status()).isEqualTo(OutboxStatus.COMPLETED);
    }

    @Test
    void executeFailsTransiently_staysPendingWithExponentialBackoff() {
        message("m", 2);
        policy.executeFailure = new RuntimeException("transient");
        clock.set(T0.plus(Duration.ofMinutes(10)));

        coordinator.processPendingMessages();

        OutboxMessage m = stored("m");
        assertThat(m.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(m.retryCount()).isEqualTo(3);
        assertThat(m.nextRetryAt()).isEqualTo(clock.instant().plusSeconds(60)); // 2^2 × 15 s
        assertThat(m.inResolution()).isFalse();
    }

    @Test
    void justBeforeTheFourHours_executes_justAfter_resolves() {
        message("m", 0);
        policy.executeFailure = new RuntimeException("transient");

        clock.set(T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(59)));
        coordinator.processPendingMessages();
        assertThat(policy.executions).isEqualTo(1);
        assertThat(policy.compensations).isZero();

        outboxPort.makeDue("m");
        clock.set(T0.plus(FOUR_HOURS).plusSeconds(60));
        coordinator.processPendingMessages();
        assertThat(policy.executions).as("vencida la ejecución, no se vuelve a ejecutar").isEqualTo(1);
        assertThat(policy.compensations).isEqualTo(1);
        assertThat(stored("m").status()).isEqualTo(OutboxStatus.RESOLVED);
    }

    @Test
    void aSuccessfulResolution_isResolved_notQuarantined() {
        message("m", 10);
        clock.set(T0.plus(Duration.ofHours(5)));

        coordinator.processPendingMessages();

        assertThat(policy.executions).isZero();
        assertThat(policy.compensations).isEqualTo(1);
        assertThat(stored("m").status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(stored("m").retryCount()).isEqualTo(10);
    }

    // 15 (H1)
    @Test
    void aTransientResolutionFailure_isRetried_andThenResolved_withoutBlockingOtherMessages() {
        message("m", 0);
        OutboxMessage other = new OutboxMessage("other", "TEST_SAGA", "agg-2", "corr-2", "{}", OutboxStatus.PENDING, 0,
                T0.plus(Duration.ofHours(4)).plusSeconds(30), T0);
        outboxPort.save(other);
        policy.compensateFailures.add(new RuntimeException("transient once"));
        clock.set(T0.plus(Duration.ofHours(4)).plusSeconds(60));

        coordinator.processPendingMessages();

        OutboxMessage m = stored("m");
        assertThat(m.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(m.inResolution()).isTrue();
        assertThat(m.resolutionStartedAt()).isEqualTo(clock.instant());
        assertThat(m.lastFailureReason()).contains("transient once");
        assertThat(m.nextRetryAt()).isAfter(clock.instant());
        assertThat(stored("other").status()).isEqualTo(OutboxStatus.COMPLETED);

        clock.set(m.nextRetryAt());
        coordinator.processPendingMessages();

        assertThat(policy.compensations).isEqualTo(2);
        assertThat(stored("m").status()).isEqualTo(OutboxStatus.RESOLVED);
    }

    @Test
    void theResolutionBackoff_isCappedAtOneHour() {
        message("m", 20);
        policy.compensateFailures.add(new RuntimeException("transient"));
        clock.set(T0.plus(Duration.ofHours(5)));

        coordinator.processPendingMessages();

        assertThat(stored("m").nextRetryAt()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    }

    // 16
    @Test
    void whenTheResolutionWindowIsExhausted_aTransientFailureQuarantines_withAnErrorLog(CapturedOutput output) {
        message("m", 0);
        for (int i = 0; i < 50; i++) policy.compensateFailures.add(new RuntimeException("still failing"));
        clock.set(T0.plus(FOUR_HOURS).plusSeconds(1));
        coordinator.processPendingMessages();
        Instant resolutionStart = stored("m").resolutionStartedAt();

        clock.set(resolutionStart.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(59)));
        outboxPort.makeDue("m");
        coordinator.processPendingMessages();
        assertThat(stored("m").status()).as("dentro de la ventana de resolución").isEqualTo(OutboxStatus.PENDING);

        clock.set(resolutionStart.plus(FOUR_HOURS).plusSeconds(1));
        outboxPort.makeDue("m");
        coordinator.processPendingMessages();

        OutboxMessage m = stored("m");
        assertThat(m.status()).isEqualTo(OutboxStatus.QUARANTINED);
        assertThat(m.lastFailureReason()).contains("still failing");
        assertThat(output.getAll()).contains("ERROR").contains("m").contains("TEST_SAGA").doesNotContain("payload-marker");
    }

    // 17
    @Test
    void aPermanentExecuteFailure_resolvesAtOnce_withoutWaitingFourHours() {
        message("m", 0);
        policy.executeFailure = new PermanentSagaFailureException("impossible", null);
        clock.set(T0.plus(Duration.ofMinutes(1)));

        coordinator.processPendingMessages();

        assertThat(policy.compensations).isEqualTo(1);
        assertThat(stored("m").status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(stored("m").resolutionStartedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aPermanentResolutionFailure_quarantinesAtOnce(CapturedOutput output) {
        message("m", 0);
        policy.compensateFailures.add(new PermanentSagaFailureException("cannot resolve", null));
        clock.set(T0.plus(Duration.ofHours(5)));

        coordinator.processPendingMessages();

        assertThat(stored("m").status()).isEqualTo(OutboxStatus.QUARANTINED);
        assertThat(stored("m").lastFailureReason()).contains("cannot resolve");
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    void anUnknownSagaType_isIgnored() {
        outboxPort.save(new OutboxMessage("x", "UNKNOWN", "a", "c", "{}", OutboxStatus.PENDING, 0, T0, T0));

        coordinator.processPendingMessages();

        assertThat(stored("x").status()).isEqualTo(OutboxStatus.PENDING);
    }

    // 13
    @Test
    void theDefaultExecutionWindow_isFourHours() {
        assertThat(OutboxSagaCoordinator.DEFAULT_EXECUTION_WINDOW).isEqualTo("PT4H");
        // Con el servicio de conversión de Spring Boot, que convierte "PT4H" en Duration como en la aplicación
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(CoordinatorOnly.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OutboxSagaCoordinator.class).executionWindow()).isEqualTo(FOUR_HOURS);
            assertThat(context.getBean(OutboxSagaCoordinator.class).resolutionWindow()).isEqualTo(FOUR_HOURS);
        });
    }

    @Configuration
    @Import(OutboxSagaCoordinator.class)
    static class CoordinatorOnly {
        @Bean OutboxPort outboxPort() { return new InMemoryOutboxPort(); }
        @Bean SagaPolicy policy() { return new MockSagaPolicy("TEST_SAGA"); }
    }

    // --- Dobles ---

    static class InMemoryOutboxPort implements OutboxPort {
        private final Map<String, OutboxMessage> messages = new LinkedHashMap<>();

        @Override public void save(OutboxMessage message) { messages.put(message.messageId(), message); }

        @Override
        public List<OutboxMessage> fetchPendingMessages(Instant now) {
            return messages.values().stream()
                    .filter(m -> m.status() == OutboxStatus.PENDING && !m.nextRetryAt().isAfter(now))
                    .toList();
        }

        @Override public void update(OutboxMessage message) { messages.put(message.messageId(), message); }
        @Override public Optional<OutboxMessage> findById(String id) { return Optional.ofNullable(messages.get(id)); }

        @Override
        public Optional<OutboxMessage> findBySagaTypeAndCorrelationId(String sagaType, String correlationId) {
            return messages.values().stream()
                    .filter(m -> m.sagaType().equals(sagaType) && m.correlationId().equals(correlationId)).findFirst();
        }

        @Override
        public List<OutboxMessage> findQuarantined(String sagaType, int limit) {
            return messages.values().stream().filter(m -> m.status() == OutboxStatus.QUARANTINED)
                    .filter(m -> sagaType == null || m.sagaType().equals(sagaType)).limit(limit).toList();
        }

        @Override
        public long countQuarantined() {
            return messages.values().stream().filter(m -> m.status() == OutboxStatus.QUARANTINED).count();
        }

        @Override
        public boolean updateIfStatus(OutboxMessage message, OutboxStatus expected) {
            OutboxMessage current = messages.get(message.messageId());
            if (current == null || current.status() != expected) return false;
            messages.put(message.messageId(), message);
            return true;
        }

        void makeDue(String id) {
            OutboxMessage m = messages.get(id);
            messages.put(id, m.withState(m.status(), m.retryCount(), Instant.EPOCH, m.resolutionStartedAt(), m.lastFailureReason()));
        }
    }

    static class MockSagaPolicy implements SagaPolicy {
        private final String sagaType;
        int executions;
        int compensations;
        RuntimeException executeFailure;
        final List<RuntimeException> compensateFailures = new ArrayList<>();

        MockSagaPolicy(String sagaType) { this.sagaType = sagaType; }

        @Override public String getSagaType() { return sagaType; }

        @Override
        public void execute(OutboxMessage message) {
            executions++;
            if (executeFailure != null) throw executeFailure;
        }

        @Override
        public void compensate(OutboxMessage message) {
            compensations++;
            if (!compensateFailures.isEmpty()) throw compensateFailures.remove(0);
        }
    }
}
