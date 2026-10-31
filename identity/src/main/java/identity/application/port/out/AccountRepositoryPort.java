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

    /**
     * Checks if there exists any account in the database that currently holds platform administrator authority.
     *
     * @return true if at least one account has platformAuthority != null, false otherwise
     */
    boolean existsAnyWithPlatformAuthority();

    /** Cuentas con autoridad de plataforma, por {@code accountId}, como mucho {@code limit}. */
    java.util.List<Account> findPlatformAdministrators(int limit);

    /**
     * [Plan: nombre propuesto en el PR de D3] Actualización parcial de las aceptaciones del login (ADR-050 Enmienda 1,
     * parte 3, E3-3): {@code $set} solo de los campos de la aceptación que no es nula, nunca {@link #save}. Solo escribe
     * si el documento sigue {@code ACTIVE} y con el {@code passwordHash} que se validó, para no pisar un cambio de
     * contraseña ni una desactivación concurrentes.
     *
     * @param terms la aceptación de los términos que se guarda, o {@code null} para no tocarla
     * @param dataPolicy la aceptación de la autorización de datos que se guarda, o {@code null} para no tocarla
     * @return true si escribió; false si la cuenta ya no tiene ese hash o no está activa
     */
    boolean recordLegalAcceptancesIfUnchanged(AccountId accountId, identity.domain.model.PasswordHash validatedHash,
                                              identity.domain.model.LegalAcceptance terms,
                                              identity.domain.model.LegalAcceptance dataPolicy);

    /**
     * [Plan] ADR-050 Enmienda 2, E2-2: {@code $set} de {@code emailVerifiedAt} solo si la cuenta sigue {@code ACTIVE} y
     * sin verificar. Nunca {@link #save}.
     *
     * @return true si escribió; false si la cuenta ya estaba verificada, no está activa o no existe
     */
    boolean markEmailVerifiedIfUnverified(AccountId accountId, java.time.Instant verifiedAt);

    /** [Plan] ADR-050 D9: la cuenta vinculada a ese {@code sub} de Google. Nunca se busca por correo. */
    Optional<Account> findByGoogleSubject(String googleSubject);

    /**
     * [Plan] ADR-050 D9: {@code $set} de {@code googleSubject} solo si la cuenta sigue {@code ACTIVE} y sin vínculo.
     *
     * @return true si escribió
     * @throws identity.domain.exception.GoogleAccountAlreadyLinkedException si otra cuenta ya tiene ese {@code sub}
     *         (índice único {@code uq_google_subject})
     */
    boolean linkGoogleIfUnlinked(AccountId accountId, String googleSubject);

    /** [Plan] Ampliación de D9 (Manuel, #164): quita el vínculo si es ese {@code sub}; true si quitó algo. */
    boolean unlinkGoogleIfLinked(AccountId accountId, String googleSubject);
}
