package com.traceability.api.web;

import org.springframework.http.HttpStatus;

/**
 * Traducción de una excepción a HTTP (plan B6-0 §2.3). {@code title} fijo; si es {@code null}, el título es el nombre
 * de la clase de la excepción sin el sufijo {@code Exception}. El cuerpo nunca lleva el mensaje de la excepción.
 */
public record ApiErrorMapping(Class<? extends Throwable> exception, HttpStatus status, String title) {
}
