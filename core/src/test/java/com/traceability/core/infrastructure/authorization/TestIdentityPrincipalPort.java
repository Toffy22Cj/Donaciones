package com.traceability.core.infrastructure.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TestIdentityPrincipalPort implements IdentityPrincipalPort {
    private final Map<String, AuthorizationPrincipal> accounts = new HashMap<>();
    private boolean wasCalled = false;

    public void addPrincipal(String accountId, String organizationId, Set<AuthorizationRole> roles) {
        accounts.put(accountId, new AuthorizationPrincipal(accountId, organizationId, roles, null));
    }

    public boolean wasCalled() {
        return wasCalled;
    }

    public void reset() {
        wasCalled = false;
        accounts.clear();
    }

    @Override
    public AuthorizationPrincipal resolvePrincipal(String accountId) {
        this.wasCalled = true;
        AuthorizationPrincipal principal = accounts.get(accountId);
        if (principal == null) {
            throw new IllegalArgumentException("Account not found in TestIdentityPrincipalPort: " + accountId);
        }
        return principal;
    }

    /** Solo lo llama el filtro JWT (ADR-050 D4), que no corre en este módulo. */
    @Override
    public AuthorizationPrincipal resolveAuthenticatedPrincipal(String accountId, java.time.Instant issuedAt) {
        throw new UnsupportedOperationException("resolveAuthenticatedPrincipal is only used by the JWT filter");
    }
}
