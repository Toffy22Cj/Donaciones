package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.InvalidProfileFieldException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.PlatformAuthorityAlreadyGrantedException;
import identity.domain.exception.PlatformAuthorityNotHeldException;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
public class Account {
    private final AccountId accountId;
    private final Email email;
    private PasswordHash passwordHash;
    private AccountStatus status;
    private OrganizationId organizationId;
    private PlatformAuthority platformAuthority;
    /** ADR-050 D4: un token vale solo si este valor es nulo o su {@code iat} no es anterior. Nulo en cuentas existentes. */
    private Instant credentialsNotBefore;
    /** ADR-050 D1: nombre y apellido, opcionales; de 1 a {@value #PROFILE_FIELD_MAX_CODE_POINTS} puntos de código. */
    private String givenName;
    private String familyName;
    /** ADR-050 D2: versión y fecha de los términos y de la autorización de datos aceptados. Nulas en cuentas existentes. */
    private LegalAcceptance termsAcceptance;
    private LegalAcceptance dataPolicyAcceptance;

    public static final int PROFILE_FIELD_MAX_CODE_POINTS = 100;

    private Account(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status, OrganizationId organizationId, PlatformAuthority platformAuthority, Instant credentialsNotBefore) {
        this.accountId = accountId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.organizationId = organizationId;
        this.platformAuthority = platformAuthority;
        this.credentialsNotBefore = credentialsNotBefore;
    }

    /**
     * Uso exclusivo de adaptadores de persistencia — NUNCA invocar desde Application Services ni tests de dominio.
     * No aplica ninguna regla de negocio de creación.
     */
    public static Account reconstitute(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status, OrganizationId organizationId, PlatformAuthority platformAuthority, Instant credentialsNotBefore) {
        return new Account(accountId, email, passwordHash, status, organizationId, platformAuthority, credentialsNotBefore);
    }

    /** Con el perfil y las aceptaciones (ADR-050 D1, D2; plan R-5). Uso exclusivo de adaptadores de persistencia. */
    public static Account reconstitute(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status,
                                       OrganizationId organizationId, PlatformAuthority platformAuthority,
                                       Instant credentialsNotBefore, String givenName, String familyName,
                                       LegalAcceptance termsAcceptance, LegalAcceptance dataPolicyAcceptance) {
        Account account = reconstitute(accountId, email, passwordHash, status, organizationId, platformAuthority,
                credentialsNotBefore);
        account.givenName = givenName;
        account.familyName = familyName;
        account.termsAcceptance = termsAcceptance;
        account.dataPolicyAcceptance = dataPolicyAcceptance;
        return account;
    }

    /** Cuenta sin {@code credentialsNotBefore} (plan R-3 + R-2, P-R3-2: se conserva para los tests que reconstruyen cuentas). */
    public static Account reconstitute(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status, OrganizationId organizationId, PlatformAuthority platformAuthority) {
        return reconstitute(accountId, email, passwordHash, status, organizationId, platformAuthority, null);
    }

    public static Account createAccount(Email email, PasswordHash passwordHash) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        if (passwordHash == null) {
            throw new IllegalArgumentException("PasswordHash cannot be null");
        }
        return new Account(AccountId.generate(), email, passwordHash, AccountStatus.ACTIVE, null, null, null);
    }

    /**
     * ADR-050 D1: nombre y apellido. Se recortan los extremos; {@code null} borra el campo; vacío tras recortar o de más de
     * {@value #PROFILE_FIELD_MAX_CODE_POINTS} puntos de código → {@link InvalidProfileFieldException} con el campo, y
     * nada cambia. Cuenta puntos de código, no unidades UTF-16 (un emoji fuera del plano básico es uno).
     *
     * @return los nombres de los campos que cambiaron
     */
    public java.util.List<String> updateProfile(String newGivenName, String newFamilyName) {
        String given = normalizeProfileField(newGivenName, "givenName");
        String family = normalizeProfileField(newFamilyName, "familyName");
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (!java.util.Objects.equals(this.givenName, given)) {
            changed.add("givenName");
        }
        if (!java.util.Objects.equals(this.familyName, family)) {
            changed.add("familyName");
        }
        this.givenName = given;
        this.familyName = family;
        return changed;
    }

    private static String normalizeProfileField(String value, String field) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        int codePoints = stripped.codePointCount(0, stripped.length());
        if (codePoints < 1 || codePoints > PROFILE_FIELD_MAX_CODE_POINTS) {
            throw new InvalidProfileFieldException(field);
        }
        return stripped;
    }

    /** ADR-050 D2: guarda la versión y la fecha de los términos y de la autorización de datos. */
    public void acceptLegalTerms(String termsVersion, String dataPolicyVersion, Instant now) {
        this.termsAcceptance = new LegalAcceptance(termsVersion, now);
        this.dataPolicyAcceptance = new LegalAcceptance(dataPolicyVersion, now);
    }

    /** Cambia el hash y revoca todos los tokens anteriores (ADR-050 D4). */
    public void changeCredentials(PasswordHash newPasswordHash, Instant now) {
        if (this.status == AccountStatus.INACTIVE) {
            throw new InactiveAccountException("Cannot change credentials of an inactive account");
        }
        if (newPasswordHash == null) {
            throw new IllegalArgumentException("New PasswordHash cannot be null");
        }
        this.passwordHash = newPasswordHash;
        revokeTokensIssuedBefore(now);
    }

    /** Desactiva y, solo si el estado cambia, revoca todos los tokens anteriores (ADR-050 D4 y D6). */
    public boolean deactivate(Instant now) {
        if (this.platformAuthority != null) {
            throw new PlatformAdministratorDeactivationException(
                "Cannot deactivate account with platform authority: " + this.accountId.value()
            );
        }
        if (this.status != AccountStatus.INACTIVE) {
            this.status = AccountStatus.INACTIVE;
            revokeTokensIssuedBefore(now);
            return true;
        }
        return false;
    }

    /** ADR-050 D4: un token vale solo si {@code credentialsNotBefore} es nulo o {@code iat >= credentialsNotBefore}. */
    public boolean acceptsTokenIssuedAt(Instant issuedAt) {
        return this.credentialsNotBefore == null || !issuedAt.isBefore(this.credentialsNotBefore);
    }

    /**
     * ADR-050 D4: {@code max(segundo actual truncado, valor actual) + 1 s}. El {@code + 1 s} rechaza el token emitido en
     * el mismo segundo que el cambio; el {@code max} hace que el valor crezca en cada cambio.
     */
    private void revokeTokensIssuedBefore(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now cannot be null");
        }
        Instant truncated = now.truncatedTo(ChronoUnit.SECONDS);
        Instant base = this.credentialsNotBefore != null && this.credentialsNotBefore.isAfter(truncated)
                ? this.credentialsNotBefore
                : truncated;
        this.credentialsNotBefore = base.plusSeconds(1);
    }

    public boolean reactivate() {
        if (this.status != AccountStatus.ACTIVE) {
            this.status = AccountStatus.ACTIVE;
            return true;
        }
        return false;
    }

    public void joinOrganization(OrganizationId newOrganizationId) {
        if (newOrganizationId == null) {
            throw new IllegalArgumentException("OrganizationId cannot be null");
        }
        if (this.organizationId != null) {
            throw new AccountAlreadyBelongsToOrganizationException("Account already belongs to an organization");
        }
        this.organizationId = newOrganizationId;
    }

    public void leaveOrganization() {
        this.organizationId = null;
    }

    public void grantPlatformAuthority() {
        if (this.status == AccountStatus.INACTIVE) {
            throw new InactiveAccountException("Cannot grant platform authority to an inactive account");
        }
        if (this.platformAuthority != null) {
            throw new PlatformAuthorityAlreadyGrantedException("Account already has platform authority: " + this.accountId.value());
        }
        this.platformAuthority = PlatformAuthority.ADMINISTRATOR;
    }

    public void revokePlatformAuthority() {
        if (this.platformAuthority == null) {
            throw new PlatformAuthorityNotHeldException("Account does not have platform authority: " + this.accountId.value());
        }
        this.platformAuthority = null;
    }
}
