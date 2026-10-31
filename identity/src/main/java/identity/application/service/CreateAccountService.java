package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.exception.LegalVersionNotCurrentException;
import identity.domain.model.Account;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.LegalVersions;
import identity.domain.model.PlainPassword;
import identity.domain.model.PasswordHash;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class CreateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final ObjectProvider<LegalVersions> legalVersions;
    private final EmailVerificationService verifications;
    private final Clock clock;

    public CreateAccountService(AccountRepositoryPort accountRepository, 
                                PasswordHasherPort passwordHasher, 
                                AuditLogPort auditLogPort,
                                MongoTransactionRetryHelper retryHelper,
                                ObjectProvider<LegalVersions> legalVersions,
                                EmailVerificationService verifications,
                                ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.legalVersions = legalVersions;
        this.verifications = verifications;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Registro público (ADR-050 D1, D2): exige las dos versiones vigentes, la de los términos y la de la autorización de
     * datos. Una ausente o distinta de la vigente → {@link LegalVersionNotCurrentException} con el campo, antes de leer
     * nada: sin cuenta y sin auditoría. Las dos se guardan con la fecha del alta.
     * <p>
     * ADR-050 Enmienda 2, E2-1 y E2-2: la cuenta nace sin verificar. El primer token de verificación se emite en la
     * misma transacción del alta y el correo se entrega después de confirmarla; la respuesta no espera al envío.
     */
    public Account createAccount(Email email, String plainPassword, String acceptedTermsVersion,
                                 String acceptedDataPolicyVersion) {
        LegalVersions current = legalVersions.getObject();
        if (!current.terms().equals(acceptedTermsVersion)) {
            throw new LegalVersionNotCurrentException("acceptedTermsVersion");
        }
        if (!current.dataPolicy().equals(acceptedDataPolicyVersion)) {
            throw new LegalVersionNotCurrentException("acceptedDataPolicyVersion");
        }
        Created created = create(email, plainPassword, true, account -> account.acceptLegalTerms(acceptedTermsVersion,
                acceptedDataPolicyVersion, clock.instant()));
        verifications.dispatch(created.verification(), created.account().getEmail());
        return created.account();
    }

    /**
     * Alta sin aceptaciones, para la semilla de la demo y los tests (plan R-5 [Plan]). La cuenta queda sin versiones
     * aceptadas; con D3, su primer inicio de sesión las exigirá. ADR-050 Enmienda 2, E2-1 y P-E2V-2: la crea el
     * sistema, así que nace con el correo verificado y sin correo de verificación.
     */
    public Account createAccount(Email email, String plainPassword) {
        return create(email, plainPassword, false, account -> account.markEmailVerified(clock.instant())).account();
    }

    private record Created(Account account, EmailVerificationService.Issued verification) {}

    private Created create(Email email, String plainPassword, boolean selfRegistration,
                           java.util.function.Consumer<Account> beforeSave) {
        // H-P2-1: la política se comprueba antes de leer nada
        PlainPassword password = new PlainPassword(plainPassword);
        return retryHelper.executeWithRetry(() -> {
            if (accountRepository.findByEmail(email).isPresent()) {
                throw new DuplicateEmailException("Email is already registered");
            }

            PasswordHash passwordHash = passwordHasher.hash(password.value());
            Account newAccount = Account.createAccount(email, passwordHash);
            beforeSave.accept(newAccount);

            accountRepository.save(newAccount);

            AuditActor actor = new AuditActor.AccountAuditActor(newAccount.getAccountId());
            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    clock.instant(),
                    actor,
                    newAccount.getAccountId(),
                    null,
                    AuditAction.ACCOUNT_CREATED,
                    // ADR-050 D10: sin el correo (las entradas anteriores no se tocan)
                    Map.of("selfRegistration", true)
            );
            auditLogPort.record(auditLog);

            return new Created(newAccount, selfRegistration
                    ? verifications.issueWithinTransaction(newAccount.getAccountId(), clock.instant()) : null);
        });
    }
}
