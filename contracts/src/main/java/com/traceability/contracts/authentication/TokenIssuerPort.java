package com.traceability.contracts.authentication;

import java.time.Instant;

/**
 * Emisión del token de sesión (ADR-038 §2.7; ADR-047).
 */
public interface TokenIssuerPort {

    /**
     * Equivale a {@link #issue(String, Instant)} con la hora actual.
     *
     * @throws TokenIssuanceException si el token no puede emitirse; nunca es un fallo de autenticación (ID01-D4)
     */
    String issue(String accountId);

    /**
     * Token con {@code iat = issuedAt} y {@code exp = issuedAt + ttl} (ADR-050 D5: el token que devuelve el cambio de
     * contraseña sale con {@code iat = credentialsNotBefore}).
     *
     * @throws TokenIssuanceException si el token no puede emitirse
     */
    String issue(String accountId, Instant issuedAt);
}
