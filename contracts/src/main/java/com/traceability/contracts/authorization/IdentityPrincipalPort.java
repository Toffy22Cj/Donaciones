package com.traceability.contracts.authorization;

import java.time.Instant;

public interface IdentityPrincipalPort {
    AuthorizationPrincipal resolvePrincipal(String accountId);

    /**
     * Lo mismo que {@link #resolvePrincipal(String)} y, además, rechaza un token emitido ({@code iat}) antes del
     * {@code credentialsNotBefore} de la cuenta (ADR-050 D4). Solo lo llama el filtro JWT. Abstracto a propósito: un
     * {@code default} que ignorara {@code issuedAt} dejaría pasar tokens revocados.
     */
    AuthorizationPrincipal resolveAuthenticatedPrincipal(String accountId, Instant issuedAt);
}
