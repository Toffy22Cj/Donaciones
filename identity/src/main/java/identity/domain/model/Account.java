package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InactiveAccountException;
import lombok.Getter;

@Getter
public class Account {
    private final AccountId accountId;
    private final Email email;
    private PasswordHash passwordHash;
    private AccountStatus status;
    private OrganizationId organizationId;

    private Account(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status, OrganizationId organizationId) {
        this.accountId = accountId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.organizationId = organizationId;
    }

    /**
     * Uso exclusivo de adaptadores de persistencia — NUNCA invocar desde Application Services ni tests de dominio.
     * No aplica ninguna regla de negocio de creación.
     */
    public static Account reconstitute(AccountId accountId, Email email, PasswordHash passwordHash, AccountStatus status, OrganizationId organizationId) {
        return new Account(accountId, email, passwordHash, status, organizationId);
    }

    public static Account createAccount(Email email, PasswordHash passwordHash) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        if (passwordHash == null) {
            throw new IllegalArgumentException("PasswordHash cannot be null");
        }
        return new Account(AccountId.generate(), email, passwordHash, AccountStatus.ACTIVE, null);
    }

    public void changeCredentials(PasswordHash newPasswordHash) {
        if (this.status == AccountStatus.INACTIVE) {
            throw new InactiveAccountException("Cannot change credentials of an inactive account");
        }
        if (newPasswordHash == null) {
            throw new IllegalArgumentException("New PasswordHash cannot be null");
        }
        this.passwordHash = newPasswordHash;
    }

    public void deactivate() {
        if (this.status != AccountStatus.INACTIVE) {
            this.status = AccountStatus.INACTIVE;
        }
    }

    public void reactivate() {
        if (this.status != AccountStatus.ACTIVE) {
            this.status = AccountStatus.ACTIVE;
        }
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
}
