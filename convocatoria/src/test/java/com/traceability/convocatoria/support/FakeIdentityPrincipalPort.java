package com.traceability.convocatoria.support;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake de test de {@link IdentityPrincipalPort} (contracts). Una cuenta desconocida lanza
 * {@link UnknownAccountException}, que representa la excepción propia del puerto real (X2).
 */
public class FakeIdentityPrincipalPort implements IdentityPrincipalPort {

    private final Map<String, AuthorizationPrincipal> principals = new ConcurrentHashMap<>();

    public void register(String accountId, String organizationId, AuthorizationRole... roles) {
        principals.put(accountId, new AuthorizationPrincipal(accountId, organizationId, Set.of(roles), null));
    }

    public void clear() {
        principals.clear();
    }

    @Override
    public AuthorizationPrincipal resolvePrincipal(String accountId) {
        AuthorizationPrincipal principal = principals.get(accountId);
        if (principal == null) {
            throw new UnknownAccountException(accountId);
        }
        return principal;
    }

    /** Solo lo llama el filtro JWT (ADR-050 D4), que no corre en este módulo. */
    @Override
    public AuthorizationPrincipal resolveAuthenticatedPrincipal(String accountId, java.time.Instant issuedAt) {
        throw new UnsupportedOperationException("resolveAuthenticatedPrincipal is only used by the JWT filter");
    }

    public static class UnknownAccountException extends RuntimeException {
        public UnknownAccountException(String accountId) {
            super("Unknown account " + accountId);
        }
    }
}
