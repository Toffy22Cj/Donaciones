package com.traceability.app.application.media;

/** Tamaño o dimensiones por encima del máximo (ADR-051 F4 y F8): 413 {@code ImageTooLarge}. */
public class ImageTooLargeException extends RuntimeException {

    public ImageTooLargeException(String message) {
        super(message);
    }
}
