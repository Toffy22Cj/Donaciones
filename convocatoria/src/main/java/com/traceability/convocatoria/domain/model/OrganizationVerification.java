package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.OrganizationNotVerifiedException;

/**
 * Precondición de dominio "organización {@code VERIFIED}" para crear convocatoria e intención
 * (ADR-037 §2.6, §5; X1, implementation_plan.md §11). El dato lo aporta un puerto de salida propio del módulo;
 * su fuente real pertenece a ADR-038.
 */
public final class OrganizationVerification {

    private OrganizationVerification() {
    }

    public static void requireVerified(String organizationRef, boolean verified) {
        if (!verified) {
            throw new OrganizationNotVerifiedException("Organization " + organizationRef + " is not VERIFIED");
        }
    }
}
