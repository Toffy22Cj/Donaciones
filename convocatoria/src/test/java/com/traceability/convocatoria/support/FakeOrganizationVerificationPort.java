package com.traceability.convocatoria.support;

import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake de test del puerto X1 (implementation_plan.md §11, §16 punto 6): de test, no de producción.
 * Por defecto toda organización está verificada salvo las marcadas.
 */
public class FakeOrganizationVerificationPort implements OrganizationVerificationPort {

    private final Set<String> unverified = ConcurrentHashMap.newKeySet();

    public void markUnverified(String organizationRef) {
        unverified.add(organizationRef);
    }

    public void clear() {
        unverified.clear();
    }

    @Override
    public boolean isVerified(String organizationRef) {
        return !unverified.contains(organizationRef);
    }
}
