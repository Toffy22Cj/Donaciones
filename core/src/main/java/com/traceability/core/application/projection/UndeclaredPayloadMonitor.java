package com.traceability.core.application.projection;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.infrastructure.projection.ProjectionEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payloads que llegan a un manejador sin estar declarados como tratados ni ignorados (B-PROJ). El manejador no lanza
 * (una excepción permanente en el evento N bloquearía el N+1 por hueco durante 4 horas, ADR-042): avanza la
 * secuencia, y aquí queda un <b>log de error</b> y un contador por manejador, expuesto por JMX en
 * {@code ProjectionRetryScheduler}. Nunca en silencio. {@code ProjectionPayloadContractTest} lo hace inalcanzable en
 * CI.
 */
@Component
public class UndeclaredPayloadMonitor {

    private static final Logger log = LoggerFactory.getLogger(UndeclaredPayloadMonitor.class);

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    /** Registra el payload si el manejador no lo declara; devuelve si estaba declarado. */
    public boolean checkDeclared(ProjectionEventHandler handler, DomainEventPayload payload) {
        Class<? extends DomainEventPayload> type = payload.getClass();
        if (handler.handledPayloads().contains(type) || handler.ignoredPayloads().contains(type)) {
            return true;
        }
        long total = counts.computeIfAbsent(handler.getHandlerName(), k -> new AtomicLong()).incrementAndGet();
        log.error("Undeclared payload {} reached {}: sequence advanced without projecting it (undeclared so far: {})",
                type.getName(), handler.getHandlerName(), total);
        return false;
    }

    public long total() {
        return counts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public Map<String, Long> byHandler() {
        Map<String, Long> snapshot = new TreeMap<>();
        counts.forEach((handler, count) -> snapshot.put(handler, count.get()));
        return snapshot;
    }
}
