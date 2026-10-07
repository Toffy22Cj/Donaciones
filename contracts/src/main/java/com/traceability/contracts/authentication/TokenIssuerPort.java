package com.traceability.contracts.authentication;

/**
 * Emisión del token de sesión (ADR-038 §2.7; ADR-047).
 */
public interface TokenIssuerPort {

    /**
     * @throws TokenIssuanceException si el token no puede emitirse; nunca es un fallo de autenticación (ID01-D4)
     */
    String issue(String accountId);
}
