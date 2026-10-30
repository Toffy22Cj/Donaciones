package com.traceability.app.web.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Carga del cursor v2 del descubrimiento (ficha R-1 §5; plan R-1 §2), antes de cifrarla con {@link DiscoveryCursorCodec}:
 * los filtros y el orden de la petición que la emitió ({@code q} ya normalizado, {@code donationType} como nombre del
 * enum, {@code sort} como valor de la API o nulo con el orden por defecto) y la última convocatoria devuelta
 * ({@code lastSortValue} en ISO-8601, nulo con el orden por defecto; {@code lastPublicCode}).
 * <p>
 * Un cursor v1 es el {@code publicCode} sin más; esta clase solo reconoce JSON con {@code v = 2} y {@code lastPublicCode}.
 */
public record DiscoveryCursorPayload(int v, String q, String donationType, String sort, String lastSortValue,
                                     String lastPublicCode) {

    public static final int VERSION = 2;

    public String toJson(ObjectMapper json) {
        try {
            return json.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir el cursor", e);
        }
    }

    /** Vacío si el texto no es una carga v2 con su {@code lastPublicCode}, o trae campos desconocidos. */
    public static Optional<DiscoveryCursorPayload> fromJson(String text, ObjectMapper json) {
        if (text == null || !text.startsWith("{")) {
            return Optional.empty();
        }
        try {
            DiscoveryCursorPayload payload = json.readerFor(DiscoveryCursorPayload.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(text);
            return payload.v() == VERSION && payload.lastPublicCode() != null ? Optional.of(payload) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
