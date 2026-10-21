package com.traceability.contracts.media;

import java.util.Optional;

/**
 * Almacenamiento de los bytes de una imagen (ADR-051 F9). La imagen nunca va a MongoDB ni a la blockchain: los módulos
 * guardan solo su referencia y los metadatos viven en {@code media_objects}, propiedad del adaptador de {@code app}.
 * <p>
 * Contrato que cumplen todos los adaptadores:
 * <ul>
 *   <li>{@link #put} es idempotente para la misma clave y los mismos bytes (ADR-051 F2), lo que permite reintentar un
 *       guardado ambiguo;</li>
 *   <li>{@link #get} de una clave inexistente devuelve {@link Optional#empty()};</li>
 *   <li>{@link #delete} de una clave inexistente no falla.</li>
 * </ul>
 * Cualquier fallo del proveedor se informa con {@link MediaStorageUnavailableException}.
 */
public interface MediaStoragePort {

    void put(String objectKey, byte[] content, String contentType);

    Optional<byte[]> get(String objectKey);

    void delete(String objectKey);
}
