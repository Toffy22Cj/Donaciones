package com.traceability.convocatoria.application.command;

/**
 * Resultado original de crear convocatoria, devuelto también ante un duplicado (N12, Enmienda §3.5).
 */
public record CreateConvocatoriaResult(String campaignRef, String publicCode) {
}
