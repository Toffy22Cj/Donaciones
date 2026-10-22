package com.traceability.app.application.media;

/**
 * Sin logo que servir (ADR-051 F6 y Enmienda 1; plan de la Tarea 2, D-T2-1): convocatoria no visible por su código,
 * organización sin logo, metadato borrado o bytes ausentes en el proveedor. Es la única causa de 404 en las dos rutas
 * del logo, para que todas sus respuestas 404 sean idénticas; nunca 410, que es solo de la evidencia. El mensaje no
 * lleva códigos ni identificadores.
 */
public class OrganizationLogoNotFoundException extends RuntimeException {

    public OrganizationLogoNotFoundException() {
        super("organization logo not found");
    }
}
