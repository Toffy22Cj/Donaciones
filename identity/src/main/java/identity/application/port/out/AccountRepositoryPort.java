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
}
