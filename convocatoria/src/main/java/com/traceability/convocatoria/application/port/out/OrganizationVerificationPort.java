package com.traceability.convocatoria.application.port.out;

/**
 * Puerto de salida propio de {@code convocatoria} para conocer si una organización está {@code VERIFIED}
 * (X1, implementation_plan.md §11). No amplía {@code AuthorizationPrincipal}. La fuente del dato es
 * {@code Organization.verificationStatus} de {@code identity} (ADR-038 §2.5); la implementación de producción es
 * {@code OrganizationVerificationAdapter}, compuesta en {@code app}: solo {@code VERIFIED} responde {@code true} y una
 * organización inexistente responde {@code false}. Los tests del módulo usan un fake.
 */
public interface OrganizationVerificationPort {

    boolean isVerified(String organizationRef);
}
