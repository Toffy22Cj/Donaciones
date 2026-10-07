package identity.domain.model;

import identity.domain.exception.InvalidMemberRoleException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Invitación a incorporarse a una organización (ADR-049 D2). Solo se invita como {@code ADMINISTRATOR} o
 * {@code EMPLOYEE} (Carlos); el {@code REPRESENTATIVE} nace con la organización. El email se guarda en minúsculas y el
 * token solo como hash. Caduca a los {@value #TTL_DAYS} días.
 */
public final class OrganizationInvitation {

    public static final int TTL_DAYS = 7;
    public static final Duration TTL = Duration.ofDays(TTL_DAYS);

    private final String invitationId;
    private final OrganizationId organizationId;
    private final String email;
    private final Role role;
    private final String tokenHash;
    private final AccountId invitedBy;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final InvitationStatus status;
    private final InvitationDelivery delivery;

    private OrganizationInvitation(String invitationId, OrganizationId organizationId, String email, Role role,
                                   String tokenHash, AccountId invitedBy, Instant createdAt, Instant expiresAt,
                                   InvitationStatus status, InvitationDelivery delivery) {
        this.invitationId = Objects.requireNonNull(invitationId);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.email = Objects.requireNonNull(email);
        this.role = Objects.requireNonNull(role);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.invitedBy = invitedBy;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.status = Objects.requireNonNull(status);
        this.delivery = Objects.requireNonNull(delivery);
    }

    /** Nueva invitación {@code PENDING}; {@code role} debe ser invitable ({@link #invitableRole(String)}). */
    public static OrganizationInvitation issue(String invitationId, OrganizationId organizationId, Email email, Role role,
                                               InvitationToken token, AccountId invitedBy, Instant now) {
        if (role != Role.ADMINISTRATOR && role != Role.EMPLOYEE) {
            throw new InvalidMemberRoleException("Only ADMINISTRATOR or EMPLOYEE can be invited");
        }
        return new OrganizationInvitation(invitationId, organizationId, normalize(email), role, token.hash(), invitedBy,
                now, now.plus(TTL), InvitationStatus.PENDING, InvitationDelivery.PENDING);
    }

    /** Uso exclusivo de adaptadores de persistencia. */
    public static OrganizationInvitation reconstitute(String invitationId, OrganizationId organizationId, String email,
                                                      Role role, String tokenHash, AccountId invitedBy, Instant createdAt,
                                                      Instant expiresAt, InvitationStatus status,
                                                      InvitationDelivery delivery) {
        return new OrganizationInvitation(invitationId, organizationId, email, role, tokenHash, invitedBy, createdAt,
                expiresAt, status, delivery);
    }

    /** {@code ADMINISTRATOR} o {@code EMPLOYEE}, exactamente; cualquier otro texto, {@link InvalidMemberRoleException}. */
    public static Role invitableRole(String role) {
        if ("ADMINISTRATOR".equals(role)) {
            return Role.ADMINISTRATOR;
        }
        if ("EMPLOYEE".equals(role)) {
            return Role.EMPLOYEE;
        }
        throw new InvalidMemberRoleException("Role must be ADMINISTRATOR or EMPLOYEE");
    }

    /** Minúsculas (DD-62): la invitación y la cuenta se comparan sin distinguir mayúsculas. */
    public static String normalize(Email email) {
        return email.value().toLowerCase(Locale.ROOT);
    }

    /** Pendiente y sin caducar en {@code now}. */
    public boolean isOpenAt(Instant now) {
        return status == InvitationStatus.PENDING && now.isBefore(expiresAt);
    }

    /** ¿Es para esta cuenta? Compara en minúsculas (ADR-049 D4). */
    public boolean isFor(Email accountEmail) {
        return email.equals(normalize(accountEmail));
    }

    /** {@code m***@dominio}: suficiente para reconocerla en la lista sin mostrar el email (DD-65). */
    public String maskedEmail() {
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    public String getInvitationId() { return invitationId; }
    public OrganizationId getOrganizationId() { return organizationId; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public String getTokenHash() { return tokenHash; }
    public AccountId getInvitedBy() { return invitedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public InvitationStatus getStatus() { return status; }
    public InvitationDelivery getDelivery() { return delivery; }

    @Override
    public String toString() {
        return "OrganizationInvitation[" + invitationId + ", " + role + ", " + status + "]";
    }
}
