package com.traceability.api.web.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-053 D2: ventana deslizante exacta en memoria (una instancia). Por cada clave guarda los instantes de las
 * peticiones aceptadas dentro de la ventana, como mucho {@code limit} por clave: la petición {@code limit + 1} dentro
 * de la ventana se rechaza con el tiempo que falta para que salga la más antigua.
 * <p>
 * Concurrencia: cada clave se decide bajo el monitor de su propia cola, así que dos hilos sobre la misma clave nunca
 * aceptan más de {@code limit} (ADR-053 D2). La limpieza ({@link #sweep()}) borra las claves sin peticiones dentro de
 * su ventana. Si el número de claves llega a {@code maxKeys} incluso después de limpiar, una clave nueva se
 * <b>rechaza</b> (falla cerrado) en lugar de crecer sin límite (ADR-053 D5); las claves ya presentes siguen igual.
 */
public final class SlidingWindowRateLimiter {

    /** Resultado de una petición: aceptada, o rechazada con el tiempo de espera (redondeado hacia arriba, ≥ 1 s). */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final class Window {
        final ArrayDeque<Long> acceptedAtMillis = new ArrayDeque<>();
        long windowMillis;
        /** La limpieza la sacó del mapa: quien la tenga en la mano vuelve a buscar la vigente. */
        boolean removed;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxKeys;
    /** Con el mapa lleno, una limpieza forzada como mucho por segundo: un ataque con claves nuevas no la repite en cada petición. */
    private volatile long lastForcedSweepMillis;

    public SlidingWindowRateLimiter(Clock clock, int maxKeys) {
        if (maxKeys <= 0) {
            throw new RateLimitConfigurationException("traceability.rate-limit.max-keys must be positive");
        }
        this.clock = clock;
        this.maxKeys = maxKeys;
        // la primera limpieza forzada puede correr enseguida (sin restar a Long.MIN_VALUE, que desborda)
        this.lastForcedSweepMillis = clock.millis() - 1000;
    }

    public Decision tryAcquire(String key, int limit, Duration window) {
        long windowMillis = window.toMillis();
        while (true) {
            Window w = windows.get(key);
            if (w == null) {
                if (windows.size() >= maxKeys) {
                    long now = clock.millis();
                    if (now - lastForcedSweepMillis >= 1000) {
                        lastForcedSweepMillis = now;
                        sweep();
                    }
                    if (windows.size() >= maxKeys) {
                        return new Decision(false, Math.max(1, ceilSeconds(windowMillis)));
                    }
                }
                w = windows.computeIfAbsent(key, k -> new Window());
            }
            synchronized (w) {
                if (w.removed) {
                    continue;
                }
                long now = clock.millis();
                w.windowMillis = windowMillis;
                evictExpired(w, now);
                if (w.acceptedAtMillis.size() < limit) {
                    w.acceptedAtMillis.addLast(now);
                    return Decision.ALLOWED;
                }
                long oldest = w.acceptedAtMillis.peekFirst();
                return new Decision(false, Math.max(1, ceilSeconds(oldest + windowMillis - now)));
            }
        }
    }

    /** Borra las claves sin ninguna petición dentro de su ventana. La programa {@link RateLimitConfig}. */
    public void sweep() {
        long now = clock.millis();
        windows.forEach((key, w) -> {
            synchronized (w) {
                evictExpired(w, now);
                if (w.acceptedAtMillis.isEmpty() && windows.remove(key, w)) {
                    w.removed = true;
                }
            }
        });
    }

    /** Número de claves vigiladas (para las pruebas de memoria y la observabilidad). */
    public int trackedKeys() {
        return windows.size();
    }

    private static void evictExpired(Window w, long now) {
        while (!w.acceptedAtMillis.isEmpty() && w.acceptedAtMillis.peekFirst() + w.windowMillis <= now) {
            w.acceptedAtMillis.pollFirst();
        }
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999) / 1000;
    }
}
