package identity.domain.model;

public enum AuditAction {
    ACCOUNT_CREATED,
    CREDENTIALS_CHANGED,
    ACCOUNT_DEACTIVATED,
    ACCOUNT_REACTIVATED,
    ORGANIZATION_CREATED,
    REPRESENTATIVE_TRANSFERRED,
    ADMINISTRATOR_ASSIGNED,
    ADMINISTRATOR_REMOVED,
    EMPLOYEE_ADDED,
    EMPLOYEE_REMOVED,
    MEMBER_REMOVED,
    PLATFORM_AUTHORITY_GRANTED,
    PLATFORM_AUTHORITY_REVOKED,
    BOOTSTRAP_PLATFORM_AUTHORITY,
    ORGANIZATION_VERIFIED,
    ORGANIZATION_REJECTED,
    ORGANIZATION_INFORMATION_REQUESTED,
    // ADR-049: invitaciones (los datos no llevan el email ni el token)
    INVITATION_CREATED,
    INVITATION_REVOKED,
    INVITATION_ACCEPTED,
    // ADR-050 D1: perfil (nombre y apellido); los datos solo nombran los campos que cambiaron, nunca sus valores
    PROFILE_UPDATED,
    // ADR-050 Enmienda 2, E2-2: correo verificado; los datos nunca llevan el correo
    EMAIL_VERIFIED
}
