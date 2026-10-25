package com.traceability.core.application.port.out;

import com.traceability.core.domain.event.DomainEventPayload;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Una página de eventos de unos streams y unos tipos, para la actividad de la organización (ficha R-6, G-8; plan D,
 * §2.2): una consulta por página, por {@code recordedAt} descendente y, en un empate, por {@code eventId} ascendente.
 * {@code recordedAt} solo sirve para listar (no es un orden de dominio) y se compara como texto, igual que lo ordena
 * Mongo. Solo lectura.
 */
public interface ActivityEventsPort {

    /** El payload, tipado como en {@code EventStorePort.loadStream}; {@code occurredAt} es el del hecho. */
    record ActivityEvent(String eventId, String streamId, String eventType, Instant occurredAt, String recordedAt,
                         DomainEventPayload payload) {}

    /**
     * @param beforeRecordedAt sin cursor, {@code null}; con cursor, solo eventos posteriores en el orden: con
     *                         {@code recordedAt} menor, o igual y con {@code eventId} mayor que {@code afterEventId}
     * @param afterEventId     {@code null} si el cursor no es de esta fuente: entonces solo {@code recordedAt} menor
     */
    List<ActivityEvent> findPage(Collection<String> streamIds, Collection<String> eventTypes, String beforeRecordedAt,
                                 String afterEventId, int limit);
}
