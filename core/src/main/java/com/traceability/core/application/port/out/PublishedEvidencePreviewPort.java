package com.traceability.core.application.port.out;

import java.util.Optional;

/**
 * La copia censurada de la foto de entrega que se puede ver en el seguimiento público (ADR-021-D Enmienda 2, E5; ADR-051
 * Enmienda 2, Tarea 4). Solo lectura. {@code api} solo puede depender de puertos de {@code core} (ADR-020), por eso esta
 * interfaz vive aquí; la implementa {@code app}, que es quien conoce el almacenamiento de imágenes.
 *
 * <p>Las tres condiciones de E5 se cumplen <b>todas</b> o no hay nada: (1) la copia existe, (2) está publicada y (3) la
 * original no está retirada por F7. En cualquier otro caso —sin copia, sin publicar, despublicada, retirada o sin foto de
 * entrega— la respuesta es la misma: vacío. El puerto <b>no distingue</b> ni dice por qué (E7) y nunca devuelve quién publicó.
 */
public interface PublishedEvidencePreviewPort {

    /** {@code true} solo si se cumplen las tres condiciones de E5 para el activo (identificador interno). */
    boolean hasPublishedPreview(String assetId);

    /** Los bytes (PNG) de la copia, solo si se cumplen las tres condiciones; vacío en cualquier otro caso. */
    Optional<byte[]> findPublishedPreview(String assetId);
}
