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
        principals.put(accountId, new AuthorizationPrincipal(accountId, organizationId, Set.of(roles)));
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

    public static class UnknownAccountException extends RuntimeException {
        public UnknownAccountException(String accountId) {
            super("Unknown account " + accountId);
        }
    }
}
