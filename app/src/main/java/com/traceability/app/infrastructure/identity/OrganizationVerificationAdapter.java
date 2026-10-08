package com.traceability.app.infrastructure.identity;

import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.OrganizationId;
import identity.domain.model.VerificationStatus;
import org.springframework.stereotype.Component;

/**
 * Adaptador de composición de {@link OrganizationVerificationPort} (X1, implementation_plan.md §11) contra el
 * Aggregate {@code Organization} de {@code identity} (ADR-038 §2.5). Vive en {@code app} para que
 * {@code convocatoria} no dependa de {@code identity}.
 *
 * <p>Solo lee: la autoridad sobre el estado de verificación sigue siendo {@code Organization}; aquí no se duplica.
 * Responde {@code true} exclusivamente para {@link VerificationStatus#VERIFIED}. Una organización inexistente
 * (o una referencia vacía) responde {@code false}: una organización desconocida nunca satisface la precondición.
 */
@Component
public class OrganizationVerificationAdapter implements OrganizationVerificationPort {

    private final OrganizationRepositoryPort organizationRepositoryPort;

    public OrganizationVerificationAdapter(OrganizationRepositoryPort organizationRepositoryPort) {
        this.organizationRepositoryPort = organizationRepositoryPort;
    }

    @Override
    public boolean isVerified(String organizationRef) {
        if (organizationRef == null || organizationRef.isBlank()) {
            return false;
        }
        try {
            return organizationRepositoryPort.findById(new OrganizationId(organizationRef))
                    .getVerificationStatus() == VerificationStatus.VERIFIED;
        } catch (OrganizationNotFoundException e) {
            return false;
        }
    }
}
