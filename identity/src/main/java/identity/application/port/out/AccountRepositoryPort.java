package identity.application.port.out;

import identity.domain.exception.AccountNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.Email;

import java.util.Optional;

public interface AccountRepositoryPort {
    /**
     * Finds an account by its unique ID.
     * @param accountId the account ID to search for
     * @return the account if found
     * @throws AccountNotFoundException if the account does not exist
     */
    Account findById(AccountId accountId);

    /**
     * Finds an account by its email address.
     * @param email the email to search for
     * @return an Optional containing the account if found, or empty otherwise
     */
    Optional<Account> findByEmail(Email email);

    /**
     * Saves a new or modified account.
     * @param account the account to save
     */
    void save(Account account);

    /**
     * Atomically grants platform administrator authority to an active account that currently does not have it.
     *
     * @param accountId ID of the account to grant authority to
     * @return true if the conditional write updated the document, false if the document does not exist,
     *         is not active, or already has platform authority
     */
    boolean grantPlatformAuthorityIfAbsent(AccountId accountId);

    /**
     * Atomically revokes platform administrator authority from an account that currently holds it.
     *
     * @param accountId ID of the account to revoke authority from
     * @return true if the conditional write updated the document, false if the document does not exist
     *         or does not hold platform administrator authority
     */
    boolean revokePlatformAuthorityIfHeld(AccountId accountId);
}

