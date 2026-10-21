package com.traceability.app.application.media;

/**
 * Resultado de la limpieza de ADR-051 F4: los bytes vueltos a codificar sin metadatos, su formato, sus dimensiones y
 * el SHA-256 de esos bytes (64 hexadecimales en minúscula).
 */
public record SanitizedImage(byte[] content, String contentType, int width, int height, String sha256) {
}
