package com.traceability.app.application.media;

import java.time.Instant;

/**
 * Una decisión de publicación de la copia censurada (ADR-051 Enmienda 2, E2-5): {@code PUBLISH} (con
 * {@code confirmNoIdentifiablePerson = true}) o {@code UNPUBLISH} (con {@code null}), cuándo y de quién ({@code by} es el
 * {@code accountId}). El historial solo se escribe añadiendo: ningún método del almacén reemplaza o borra entradas.
 */
public record PublicationDecision(Action action, Instant at, String by, Boolean confirmNoIdentifiablePerson) {

    public enum Action { PUBLISH, UNPUBLISH }
}
