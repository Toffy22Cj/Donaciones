package com.traceability.contracts.authentication;

import java.time.Instant;

/**
 * Resultado de {@link AuthenticateAccountPort#authenticate} (ADR-050 Enmienda 1, E1-1; plan P-R3-3). {@code
 * credentialsNotBefore} es el de la misma cuenta, leído en la misma lectura que validó la contraseña; nulo si la cuenta
 * no lo tiene. Con él, el login emite con {@code iat = max(segundo actual truncado, credentialsNotBefore)} (E1-2).
 */
public record AuthenticatedAccount(String accountId, Instant credentialsNotBefore) {}
