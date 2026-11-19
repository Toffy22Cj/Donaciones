package com.traceability.core.domain.shared;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * De dónde sale el {@code occurredAt} de un hecho: <b>siempre el reloj real</b>, salvo dentro de
 * {@link #callAt(Instant, Supplier)} con la fuente armada.
 * <p>
 * Un sistema de trazabilidad no debe poder fechar hechos en el pasado. Por eso fechar en el pasado exige las cuatro
 * cosas a la vez (plan del paso 2, aprobado por Manuel el 2026-11-16; pendiente del visto bueno de Carlos):
 * <ul>
 *   <li><b>armado explícito</b>: {@link #arm()} solo lo llama el perfil {@code demo-historico} al arrancar; sin armar,
 *   {@code callAt} lanza {@link SimulatedTimeNotEnabledException} y no ejecuta nada;</li>
 *   <li><b>nunca en el futuro</b>: {@link SimulatedTimeInFutureException}; así {@code occurredAt <= recordedAt} se
 *   cumple por construcción, porque {@code recordedAt} lo pone el event store con el reloj real;</li>
 *   <li><b>solo en el hilo que lo pide</b>: los demás hilos (peticiones, planificadores, sagas) ven la hora real;</li>
 *   <li><b>solo durante el bloque</b>: al salir, también con excepción, el hilo vuelve a la hora real.</li>
 * </ul>
 * Una regla de arquitectura en {@code app} comprueba que nadie fuera de la semilla histórica llama a {@code arm} ni
 * a {@code callAt}. {@code recordedAt}, la cadena de hashes y el anclaje no pasan por aquí.
 */
public final class EventTime {

    private static final AtomicBoolean ARMED = new AtomicBoolean(false);
    private static final ThreadLocal<Instant> SIMULATED = new ThreadLocal<>();

    private EventTime() {}

    /** Permiso para fechar en el pasado; al cerrarlo, la fuente vuelve a estar desarmada. */
    public interface Armed extends AutoCloseable {
        @Override void close();
    }

    /** El instante de un hecho que ocurre ahora: el reloj real, salvo dentro de {@link #callAt} en este hilo. */
    public static Instant now() {
        Instant simulated = SIMULATED.get();
        return simulated != null ? simulated : Instant.now();
    }

    /**
     * Arma la fuente. Solo puede estar armada una vez; el segundo intento falla sin tocar el primero.
     *
     * @throws IllegalStateException si ya estaba armada
     */
    public static Armed arm() {
        if (!ARMED.compareAndSet(false, true)) {
            throw new IllegalStateException("EventTime is already armed");
        }
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                ARMED.set(false);
            }
        };
    }

    public static boolean isArmed() {
        return ARMED.get();
    }

    /**
     * Ejecuta {@code action} con {@code occurredAt} como instante de los hechos que emita <b>este hilo</b>. Se pueden
     * anidar: al salir vuelve el instante del bloque exterior, o la hora real.
     *
     * @throws SimulatedTimeNotEnabledException si la fuente no está armada
     * @throws SimulatedTimeInFutureException   si {@code occurredAt} es posterior a la hora real
     */
    public static <T> T callAt(Instant occurredAt, Supplier<T> action) {
        if (!ARMED.get()) {
            throw new SimulatedTimeNotEnabledException(
                    "Simulated event time is not enabled: only the demo-historico profile arms it");
        }
        if (occurredAt == null || occurredAt.isAfter(Instant.now())) {
            throw new SimulatedTimeInFutureException("occurredAt must not be after the real time: " + occurredAt);
        }
        Instant outer = SIMULATED.get();
        SIMULATED.set(occurredAt);
        try {
            return action.get();
        } finally {
            if (outer == null) {
                SIMULATED.remove();
            } else {
                SIMULATED.set(outer);
            }
        }
    }
}
