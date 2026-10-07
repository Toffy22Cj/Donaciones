package identity.application.port.out;

import identity.domain.model.AccountId;
import identity.domain.model.InvitationDelivery;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationInvitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistencia de {@link OrganizationInvitation} (ADR-049 D2): colección {@code organization_invitations}. */
public interface OrganizationInvitationRepositoryPort {

    void insert(OrganizationInvitation invitation);

    Optional<OrganizationInvitation> findById(String invitationId);

    /** Por el SHA-256 del token (índice único). */
    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    /** Pendientes de una organización, por fecha de creación, como mucho {@code limit} (incluye las caducadas). */
    List<OrganizationInvitation> findPendingByOrganization(OrganizationId organizationId, int limit);

    /** Marca {@code REVOKED} todas las pendientes de (organización, email). Devuelve cuántas. */
    long revokePendingFor(OrganizationId organizationId, String normalizedEmail, Instant revokedAt);

    /** Escritura condicional {@code PENDING → REVOKED}. Devuelve si se aplicó. */
    boolean markRevokedIfPending(String invitationId, Instant revokedAt);

    /** Escritura condicional {@code PENDING → ACCEPTED}: de dos aceptaciones concurrentes, gana una. */
    boolean markAcceptedIfPending(String invitationId, AccountId acceptedBy, Instant acceptedAt);

    void updateDelivery(String invitationId, InvitationDelivery delivery);
}
