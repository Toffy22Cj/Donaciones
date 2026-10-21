package com.traceability.app.application.media;

/**
 * Formato no admitido o imagen que la JDK no decodifica (ADR-051 F4): 415 {@code UnsupportedImageFormat}. Solo JPEG y
 * PNG, reconocidos por sus primeros bytes (F8).
 */
public class UnsupportedImageFormatException extends RuntimeException {

    public UnsupportedImageFormatException(String message) {
        super(message);
    }
}
