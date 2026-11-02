package com.traceability.core.application.exception;

/**
 * [Plan] Enmienda 2 de ADR-029, D3: el código de municipio de un paso con ubicación (registrar, recibir o entregar) falta
 * o no está en el catálogo DIVIPOLA. Lo lanza {@code PhysicalAssetCommandService} después de autorizar y antes de
 * persistir, así que no escribe ningún evento ni reclama el {@code Command-Id}. → 400.
 */
public class UnknownMunicipalityCodeException extends RuntimeException {
    public UnknownMunicipalityCodeException() {
        super("municipalityCode is missing or not in the DIVIPOLA catalog");
    }
}
