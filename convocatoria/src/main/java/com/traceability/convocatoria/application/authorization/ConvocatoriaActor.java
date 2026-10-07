package com.traceability.convocatoria.application.authorization;

import com.traceability.contracts.authorization.AuthorizationRole;

import java.util.Set;

/**
 * Representación de actor propia del módulo, derivada de {@code AuthorizationPrincipal}; nunca
 * {@code core.domain.event.ActorRef} (ADR-037 §6).
 */
public record ConvocatoriaActor(String accountId, String organizationRef, Set<AuthorizationRole> roles) {

    public ConvocatoriaActor {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
