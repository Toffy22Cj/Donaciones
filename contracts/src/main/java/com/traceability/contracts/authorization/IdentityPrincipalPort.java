package com.traceability.contracts.authorization;

public interface IdentityPrincipalPort {
    AuthorizationPrincipal resolvePrincipal(String accountId);
}
