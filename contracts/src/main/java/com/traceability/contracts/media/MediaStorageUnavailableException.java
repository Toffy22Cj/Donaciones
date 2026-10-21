package com.traceability.contracts.media;

/**
 * El proveedor de almacenamiento no completó la operación (ADR-051 F2, F9): 503 {@code MediaStorageUnavailable}. Al
 * guardar, se lanza tras el único reintento de un fallo ambiguo, o sin reintento ante un fallo determinista.
 * <p>
 * No encadena la causa ni copia su mensaje: así ningún registro de causas puede llevar un mensaje del proveedor ni una
 * credencial (ADR-051 F1). El adaptador registra por su cuenta la clase, el estado y el código de error.
 */
public class MediaStorageUnavailableException extends RuntimeException {

    public MediaStorageUnavailableException(String message) {
        super(message);
    }
}
