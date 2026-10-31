package com.traceability.api.auth;

import com.traceability.contracts.authentication.AuthenticatedAccount;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * [Plan] ADR-050 Enmienda 1, E1-2: el {@code iat} del token que emite un inicio de sesión es {@code max(segundo actual
 * truncado, credentialsNotBefore)}; sin {@code credentialsNotBefore}, el segundo actual. Sale de {@code LoginController}
 * sin cambiar la regla, para que el login con Google (ADR-050 D9, P-G-3) emita exactamente igual.
 */
public final class LoginIssuedAt {

    private LoginIssuedAt() {
    }

    public static Instant of(Clock clock, AuthenticatedAccount account) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant credentialsNotBefore = account.credentialsNotBefore();
        return credentialsNotBefore != null && credentialsNotBefore.isAfter(now) ? credentialsNotBefore : now;
    }
}
