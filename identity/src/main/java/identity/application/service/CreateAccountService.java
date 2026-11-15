package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.GoogleIdTokenVerifierPort;
import identity.application.port.out.GoogleIdTokenVerifierPort.VerifiedGoogleIdentity;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.exception.GoogleAccountAlreadyLinkedException;
import identity.domain.exception.GoogleIdTokenInvalidException;
import identity.domain.exception.GoogleSignInUnavailableException;
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
import java.util.HashMap;
import java.util.Map;

@Service
public class CreateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final ObjectProvider<LegalVersions> legalVersions;
    private final EmailVerificationService verifications;
    private final ObjectProvider<GoogleIdTokenVerifierPort> google;
    private final Clock clock;

    public CreateAccountService(AccountRepositoryPort accountRepository, 
                                PasswordHasherPort passwordHasher, 
                                AuditLogPort auditLogPort,
                                MongoTransactionRetryHelper retryHelper,
                                ObjectProvider<LegalVersions> legalVersions,
                                EmailVerificationService verifications,
                                ObjectProvider<GoogleIdTokenVerifierPort> google,
                                ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.legalVersions = legalVersions;
        this.verifications = verifications;
        this.google = google;
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
        requireCurrentVersions(acceptedTermsVersion, acceptedDataPolicyVersion);
        Created created = create(email, plainPassword, true, Map.of(), account -> account.acceptLegalTerms(acceptedTermsVersion,
                acceptedDataPolicyVersion, clock.instant()), account -> { });
        verifications.dispatch(created.verification(), created.account().getEmail());
        return created.account();
    }

    /**
     * [Propuesta] Registro con Google (ADR-050 Enmienda 3, E3-1 a E3-4): el alta pública de siempre, pero el correo lo
     * pone el {@code idToken} de Google en lugar de la persona. Toda cuenta sigue teniendo contraseña (Q2 (a) no se
     * toca en eso: la cuenta nace con contraseña, como exige {@link Account#createAccount}).
     * <ol>
     *   <li>Las versiones legales y la política de la contraseña se comprueban <b>antes</b> de verificar el
     *       {@code idToken}: sin llamar a Google, sin cuenta y sin auditoría (como el alta con correo).</li>
     *   <li>El {@code idToken} se verifica como en el login ({@code email_verified = true}). Inválido →
     *       {@link GoogleIdTokenInvalidException} (400); Google sin responder → 503.</li>
     *   <li>Ese {@code sub} ya vinculado a otra cuenta → {@link GoogleAccountAlreadyLinkedException} (409). El correo
     *       ya registrado → {@link DuplicateEmailException} (409), como el alta con correo: quien lo recibe ha probado
     *       con Google que el correo es suyo (E3-3), y la cuenta existente no se toca ni se vincula.</li>
     *   <li>En una sola transacción: la cuenta con las aceptaciones, el correo verificado (lo prueba Google, P-G-1) y
     *       el vínculo. Audita {@code ACCOUNT_CREATED} con {@code via: GOOGLE}, {@code GOOGLE_LINKED} y
     *       {@code EMAIL_VERIFIED} con {@code via: GOOGLE_REGISTER}. Sin correo de verificación ni aviso de seguridad:
     *       la cuenta es nueva.</li>
     * </ol>
     * Nunca registra el {@code idToken}, el {@code sub} ni el correo.
     */
    public Account createAccountWithGoogle(String idToken, String plainPassword, String acceptedTermsVersion,
                                           String acceptedDataPolicyVersion) {
        requireCurrentVersions(acceptedTermsVersion, acceptedDataPolicyVersion);
        new PlainPassword(plainPassword); // H-P2-1: la política, antes de llamar a Google
        // El verificador lo pone `app` (Nimbus); los contextos de `identity` sin él no registran con Google
        GoogleIdTokenVerifierPort verifier = google.getIfAvailable();
        if (verifier == null) {
            throw new GoogleSignInUnavailableException("Google sign-in is not configured");
        }
        VerifiedGoogleIdentity identity = verifier.verify(idToken);
        if (identity.email() == null || identity.email().isBlank()) {
            throw new GoogleIdTokenInvalidException("The Google idToken carries no email");
        }
        if (accountRepository.findByGoogleSubject(identity.subject()).isPresent()) {
            throw new GoogleAccountAlreadyLinkedException("That Google account is linked to another account");
        }
        return create(new Email(identity.email()), plainPassword, false, Map.of("via", "GOOGLE"),
                account -> {
                    account.acceptLegalTerms(acceptedTermsVersion, acceptedDataPolicyVersion, clock.instant());
                    account.markEmailVerified(clock.instant());
                },
                account -> {
                    if (!accountRepository.linkGoogleIfUnlinked(account.getAccountId(), identity.subject())) {
                        throw new GoogleAccountAlreadyLinkedException("The new account could not be linked");
                    }
                    AuditActor actor = new AuditActor.AccountAuditActor(account.getAccountId());
                    auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), clock.instant(), actor,
                            account.getAccountId(), null, AuditAction.GOOGLE_LINKED, Map.of()));
                    auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), clock.instant(), actor,
                            account.getAccountId(), null, AuditAction.EMAIL_VERIFIED, Map.of("via", "GOOGLE_REGISTER")));
                }).account();
    }

    /** ADR-050 D2: una versión ausente o distinta de la vigente, con el campo nombrado, antes de leer nada. */
    private void requireCurrentVersions(String acceptedTermsVersion, String acceptedDataPolicyVersion) {
        LegalVersions current = legalVersions.getObject();
        if (!current.terms().equals(acceptedTermsVersion)) {
            throw new LegalVersionNotCurrentException("acceptedTermsVersion");
        }
        if (!current.dataPolicy().equals(acceptedDataPolicyVersion)) {
            throw new LegalVersionNotCurrentException("acceptedDataPolicyVersion");
        }
    }

    /**
     * Alta sin aceptaciones, para la semilla de la demo y los tests (plan R-5 [Plan]). La cuenta queda sin versiones
     * aceptadas; con D3, su primer inicio de sesión las exigirá. ADR-050 Enmienda 2, E2-1 y P-E2V-2: la crea el
     * sistema, así que nace con el correo verificado y sin correo de verificación.
     */
    public Account createAccount(Email email, String plainPassword) {
        return create(email, plainPassword, false, Map.of(), account -> account.markEmailVerified(clock.instant()),
                account -> { }).account();
    }

    private static Map<String, Object> auditData(Map<String, Object> extra) {
        Map<String, Object> data = new HashMap<>(extra);
        data.put("selfRegistration", true);
        return Map.copyOf(data);
    }

    private record Created(Account account, EmailVerificationService.Issued verification) {}

    private Created create(Email email, String plainPassword, boolean selfRegistration, Map<String, Object> auditExtra,
                           java.util.function.Consumer<Account> beforeSave,
                           java.util.function.Consumer<Account> afterSave) {
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
                    auditData(auditExtra)
            );
            auditLogPort.record(auditLog);
            afterSave.accept(newAccount);

            return new Created(newAccount, selfRegistration
                    ? verifications.issueWithinTransaction(newAccount.getAccountId(), clock.instant()) : null);
        });
    }
}
