package com.traceability.contracts.authentication;

/**
 * Fallo interno al emitir un token. Se responde con 500, nunca con 401 (ID01-D4). El mensaje nunca incluye material
 * criptográfico (ADR-047 D7).
 */
public class TokenIssuanceException extends RuntimeException {

    public TokenIssuanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
