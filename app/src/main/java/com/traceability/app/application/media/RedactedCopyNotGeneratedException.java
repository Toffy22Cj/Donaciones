package com.traceability.app.application.media;

/**
 * Se intentó publicar una copia que aún no existe (ADR-051 Enmienda 2, Q-T3-3 (a)): la copia se genera al abrirla, y solo se
 * publica una copia que alguien pudo ver. Responde 409 {@code RedactedCopyNotGenerated}.
 */
public class RedactedCopyNotGeneratedException extends RuntimeException {

    public RedactedCopyNotGeneratedException() {
        super("RedactedCopyNotGenerated");
    }
}
