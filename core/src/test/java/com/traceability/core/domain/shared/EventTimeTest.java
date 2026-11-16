package com.traceability.core.domain.shared;

import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fuente de tiempo de los hechos (plan del paso 2, 2026-11-16). Un sistema de trazabilidad no debe poder fechar hechos
 * en el pasado: solo se puede con la fuente armada (perfil {@code demo-historico}), nunca en el futuro, y sin fugas
 * entre hilos.
 */
class EventTimeTest {

    static final Instant PAST = Instant.parse("2026-06-01T10:00:00Z");

    /** Un agregado mínimo: solo emite un evento, para leer el {@code occurredAt} que sella {@link AggregateRoot}. */
    static final class Probe extends AggregateRoot {
        record Happened() implements DomainEventPayload {}

        enum ProbeEventType implements EventType { HAPPENED }

        DomainEvent emit() {
            raiseEvent(ProbeEventType.HAPPENED, new Happened());
            List<DomainEvent> events = getUncommittedEvents();
            return events.get(events.size() - 1);
        }

        @Override protected void apply(DomainEventPayload payload) {}
    }

    @AfterEach
    void neverLeaveItArmed() {
        assertThat(EventTime.isArmed()).as("cada test cierra su armado").isFalse();
    }

    static void assertIsRealNow(Instant instant) {
        assertThat(Duration.between(instant, Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void withoutArming_theBlockThrows_andOccurredAtIsStillTheRealTime() {
        List<String> ran = new ArrayList<>();

        assertThatThrownBy(() -> EventTime.callAt(PAST, () -> ran.add("ran")))
                .isInstanceOf(SimulatedTimeNotEnabledException.class);

        assertThat(ran).as("el bloque no llega a ejecutarse").isEmpty();
        assertThat(EventTime.isArmed()).isFalse();
        assertIsRealNow(EventTime.now());
        assertIsRealNow(new Probe().emit().occurredAt());
    }

    @Test
    void armed_insideTheBlock_theAggregateSealsTheRequestedInstant_andOutsideTheRealOne() {
        try (EventTime.Armed armed = EventTime.arm()) {
            assertThat(EventTime.isArmed()).isTrue();
            assertIsRealNow(EventTime.now());

            DomainEvent inside = EventTime.callAt(PAST, () -> {
                assertThat(EventTime.now()).isEqualTo(PAST);
                return new Probe().emit();
            });

            assertThat(inside.occurredAt()).isEqualTo(PAST);
            assertIsRealNow(EventTime.now());
            assertIsRealNow(new Probe().emit().occurredAt());
        }
    }

    @Test
    void theRealTimeComesBack_evenIfTheBlockThrows() {
        try (EventTime.Armed armed = EventTime.arm()) {
            assertThatThrownBy(() -> EventTime.callAt(PAST, () -> {
                throw new IllegalStateException("fallo dentro del bloque");
            })).isInstanceOf(IllegalStateException.class).hasMessage("fallo dentro del bloque");

            assertIsRealNow(EventTime.now());
        }
    }

    @Test
    void aFutureInstantIsRejected_andNothingRuns() {
        try (EventTime.Armed armed = EventTime.arm()) {
            List<String> ran = new ArrayList<>();

            assertThatThrownBy(() -> EventTime.callAt(Instant.now().plus(Duration.ofMinutes(1)), () -> ran.add("ran")))
                    .isInstanceOf(SimulatedTimeInFutureException.class);

            assertThat(ran).isEmpty();
            assertIsRealNow(EventTime.now());
        }
    }

    @Test
    void closingTheArming_disarmsIt_andTheBlockThrowsAgain() {
        EventTime.arm().close();

        assertThat(EventTime.isArmed()).isFalse();
        assertThatThrownBy(() -> EventTime.callAt(PAST, () -> "x")).isInstanceOf(SimulatedTimeNotEnabledException.class);
    }

    @Test
    void itCannotBeArmedTwice() {
        try (EventTime.Armed armed = EventTime.arm()) {
            assertThatThrownBy(EventTime::arm).isInstanceOf(IllegalStateException.class);
            assertThat(EventTime.isArmed()).as("el segundo intento no desarma el primero").isTrue();
        }
    }

    @Test
    void nestedBlocks_restoreTheOuterInstant() {
        Instant earlier = PAST.minus(Duration.ofDays(30));
        try (EventTime.Armed armed = EventTime.arm()) {
            EventTime.callAt(PAST, () -> {
                assertThat(EventTime.callAt(earlier, EventTime::now)).isEqualTo(earlier);
                assertThat(EventTime.now()).isEqualTo(PAST);
                return null;
            });
            assertIsRealNow(EventTime.now());
        }
    }

    @Test
    void anotherThread_neverSeesTheSimulatedInstant() throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try (EventTime.Armed armed = EventTime.arm()) {
            Instant seenByOther = EventTime.callAt(PAST, () -> {
                try {
                    return other.submit(EventTime::now).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertIsRealNow(seenByOther);
        } finally {
            other.shutdownNow();
        }
    }

    /**
     * Sin fugas en hilos reutilizados: cada hilo de un pool pequeño fecha en el pasado (a veces fallando) y el mismo
     * hilo, en su tarea siguiente, tiene que ver otra vez la hora real.
     */
    @Test
    void reusedPoolThreads_doNotLeakTheSimulatedInstantIntoTheirNextTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (EventTime.Armed armed = EventTime.arm()) {
            List<Future<String>> simulated = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                int n = i;
                simulated.add(pool.submit(() -> {
                    try {
                        return EventTime.callAt(PAST.plus(Duration.ofHours(n)), () -> {
                            if (n % 4 == 0) {
                                throw new IllegalStateException("fallo " + n);
                            }
                            assertThat(EventTime.now()).isEqualTo(PAST.plus(Duration.ofHours(n)));
                            return Thread.currentThread().getName();
                        });
                    } catch (IllegalStateException expected) {
                        return Thread.currentThread().getName();
                    }
                }));
            }
            List<String> threads = new ArrayList<>();
            for (Future<String> f : simulated) {
                threads.add(f.get());
            }
            assertThat(threads.stream().distinct().count()).as("los hilos se reutilizaron").isLessThanOrEqualTo(3);

            List<Future<Instant[]>> after = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                after.add(pool.submit(() -> new Instant[] {EventTime.now(), new Probe().emit().occurredAt()}));
            }
            for (Future<Instant[]> f : after) {
                assertIsRealNow(f.get()[0]);
                assertIsRealNow(f.get()[1]);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
