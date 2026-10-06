package com.traceability.convocatoria.application.port.out;

/**
 * Puerto de salida propio de {@code convocatoria} para conocer si una organización está {@code VERIFIED}
 * (X1, implementation_plan.md §11). No amplía {@code AuthorizationPrincipal}. Sin implementación de producción en
 * este corte: el dato no existe en {@code identity} ({@code Organization.java:16-25}) y su fuente es ADR-038; el
 * adaptador se compondrá en {@code app}. Los tests usan un fake.
 */
public interface OrganizationVerificationPort {

    boolean isVerified(String organizationRef);
}
