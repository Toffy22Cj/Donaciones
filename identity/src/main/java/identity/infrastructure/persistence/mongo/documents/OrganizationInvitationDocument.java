package identity.infrastructure.persistence.mongo.documents;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Invitación (ADR-049 D2). Solo el hash del token, con índice único; nunca el token. */
@Getter
@Setter
@Document("organization_invitations")
@CompoundIndex(name = "org_email_status_idx", def = "{'organizationId': 1, 'email': 1, 'status': 1}")
public class OrganizationInvitationDocument {
    @Id
    private String invitationId;
    private String organizationId;
    private String email;
    private String role;
    @Indexed(unique = true)
    private String tokenHash;
    private String invitedBy;
    private Instant createdAt;
    private Instant expiresAt;
    private String status;
    private String delivery;
    private String acceptedBy;
    private Instant acceptedAt;
    private Instant revokedAt;
}
