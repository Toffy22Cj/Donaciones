package com.traceability.contracts.authorization;

import java.util.Set;

public record AuthorizationPrincipal(
    String accountId,
    String organizationId,
    Set<AuthorizationRole> roles
) {}
