package com.traceability.core.application.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * DT-B-1 (plan de las deudas 1 a 3 §2; P-DT1-1 a P-DT1-3): el texto con el que el Event Store guarda {@code recordedAt}
 * y con el que G-8 compara sus instantes. Siempre 9 decimales y UTC: con años de 4 cifras, el orden del texto es el
 * cronológico, también dentro de un mismo segundo. Sin pérdida: {@code Instant.parse(of(t))} es {@code t}.
 * <p>
 * No es la forma del hash: {@link EventCanonicalMapper} sigue con {@code Instant.toString()} y el verificador parsea
 * el texto guardado, así que el hash de los eventos no cambia. Los eventos guardados antes conservan su texto (no se
 * reescriben) y se toleran al leer.
 */
public final class RecordedAtText {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private RecordedAtText() {
    }

    public static String of(Instant instant) {
        return FORMAT.format(instant);
    }
}
