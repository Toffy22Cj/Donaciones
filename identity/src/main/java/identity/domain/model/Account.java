package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InactiveAccountException;
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
