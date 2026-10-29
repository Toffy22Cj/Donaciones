package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.EmailVerificationMailPort;
import identity.application.port.out.EmailVerificationMailPort.EmailVerificationMail;
import identity.application.port.out.EmailVerificationTokenRepositoryPort;
import identity.domain.exception.EmailVerificationTokenInvalidException;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.EmailVerificationToken;
import identity.domain.model.InvitationToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * [Plan] Verificación del correo (ADR-050 Enmienda 2, E2-2; plan de la Enmienda 2 §1), con el patrón de la recuperación
 * (D8, {@link RequestPasswordResetService} y {@link ChangeCredentialsService#resetCredentials}).
 * <ul>
 *   <li>{@link #request}: el reenvío. Pase lo que pase, la ruta responde lo mismo. Solo una cuenta {@code ACTIVE} sin
 *       verificar y fuera del enfriamiento de 5 minutos recibe un token nuevo; el anterior pasa a {@code SUPERSEDED}.
 *       El correo se entrega después de que la transacción confirme.</li>
 *   <li>{@link #confirm}: token por su hash, utilizable, de una cuenta {@code ACTIVE}. Si la cuenta ya está verificada,
 *       nada cambia (P-E2V-7). Si no, en una transacción: {@code PENDING → USED} condicional (de dos confirmaciones
 *       simultáneas gana una), {@code emailVerifiedAt} y la auditoría {@code EMAIL_VERIFIED} sin el correo. Cualquier
 *       otro caso es la misma {@link EmailVerificationTokenInvalidException}. No inicia sesión.</li>
 * </ul>
 * El registro público emite el primer token con {@link #issueWithinTransaction} dentro de su propia transacción y lo
 * entrega con {@link #dispatch} después de confirmar ({@link CreateAccountService}).
 */
@Service
public class EmailVerificationService {

    private final AccountRepositoryPort accountRepository;
    private final EmailVerificationTokenRepositoryPort verificationTokens;
    private final EmailVerificationMailPort mail;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final Clock clock;

    public EmailVerificationService(AccountRepositoryPort accountRepository,
                                    EmailVerificationTokenRepositoryPort verificationTokens,
                                    EmailVerificationMailPort mail,
                                    AuditLogPort auditLogPort,
                                    MongoTransactionRetryHelper retryHelper,
                                    ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.verificationTokens = verificationTokens;
        this.mail = mail;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Un token recién emitido y su secreto, que solo vive en memoria hasta que se entrega el correo. */
    record Issued(EmailVerificationToken token, String secret) {
        @Override
        public String toString() {
            return "Issued[" + token + "]";
        }
    }

    /**
     * Sustituye el {@code PENDING} anterior y guarda uno nuevo (solo su hash). Sin transacción propia: se llama dentro de
     * la del llamador, que genera el secreto en cada intento y entrega el del intento que confirmó.
     */
    Issued issueWithinTransaction(AccountId accountId, Instant now) {
        verificationTokens.supersedePendingFor(accountId);
        InvitationToken secret = InvitationToken.generate();
        EmailVerificationToken token = EmailVerificationToken.issue(UlidCreator.getUlid().toString(), accountId,
                secret.hash(), now);
        verificationTokens.insert(token);
        return new Issued(token, secret.value());
    }

    /** Entrega el correo para enviarlo; llamar solo después de que la transacción confirme. */
    void dispatch(Issued issued, Email to) {
        mail.dispatch(new EmailVerificationMail(issued.token().getId(), to.value(), issued.secret(),
                issued.token().getExpiresAt()));
    }

    /** El reenvío (E2-2). No lanza por una cuenta inexistente, verificada, inactiva, en enfriamiento o perdida en una carrera. */
    public void request(String email) {
        Optional<Account> found = findByEmail(email);
        if (found.isEmpty() || found.get().getStatus() != AccountStatus.ACTIVE || found.get().isEmailVerified()) {
            return;
        }
        Account account = found.get();

        Optional<Issued> issued;
        try {
            issued = retryHelper.executeWithRetry(() -> {
                Instant now = clock.instant();
                Optional<EmailVerificationToken> pending = verificationTokens.findPendingByAccount(account.getAccountId());
                if (pending.isPresent() && pending.get().isInCooldownAt(now)) {
                    return Optional.<Issued>empty();
                }
                return Optional.of(issueWithinTransaction(account.getAccountId(), now));
            });
        } catch (DuplicateKeyException concurrentWinner) {
            return;
        }

        issued.ifPresent(i -> dispatch(i, account.getEmail()));
    }

    /** La confirmación (E2-2). */
    public void confirm(String token) {
        if (token == null || token.isBlank()) {
            throw new EmailVerificationTokenInvalidException();
        }
        String tokenHash = InvitationToken.hashOf(token);

        retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            EmailVerificationToken verification = verificationTokens.findByTokenHash(tokenHash)
                    .filter(t -> t.isUsableAt(now))
                    .orElseThrow(EmailVerificationTokenInvalidException::new);
            Account account = accountRepository.findById(verification.getAccountId());
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new EmailVerificationTokenInvalidException();
            }
            if (account.isEmailVerified()) {
                return;
            }
            if (!verificationTokens.markUsedIfPending(verification.getId(), now)) {
                throw new EmailVerificationTokenInvalidException();
            }
            if (accountRepository.markEmailVerifiedIfUnverified(account.getAccountId(), now)) {
                recordVerified(account.getAccountId(), now, Map.of());
            }
        });
    }

    /**
     * La recuperación confirmada (D8) también verifica (E2-2), en la transacción del llamador: si la cuenta no estaba
     * verificada, fija {@code emailVerifiedAt} y audita {@code EMAIL_VERIFIED} con {@code {via: "PASSWORD_RESET"}}
     * (P-E2V-8).
     */
    void verifyWithinPasswordReset(AccountId accountId, Instant now) {
        if (accountRepository.markEmailVerifiedIfUnverified(accountId, now)) {
            recordVerified(accountId, now, Map.of("via", "PASSWORD_RESET"));
        }
    }

    /**
     * El actor es la cuenta, que demostró controlar su correo, como en la confirmación de la recuperación (ADR-050
     * Enmienda 1, parte 2). <b>Pendiente (P-E2V-13):</b> ADR-038 §2.2 solo deja construir {@code AccountAuditActor} a las
     * clases que nombra {@code ArchitectureTest} ({@code :67-78}), y esta no está entre ellas. La Enmienda 2 no lo
     * autoriza; lo decide Manuel.
     */
    private void recordVerified(AccountId accountId, Instant now, Map<String, Object> summary) {
        auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now,
                new AuditActor.AccountAuditActor(accountId), accountId, null, AuditAction.EMAIL_VERIFIED, summary));
    }

    /** Un correo mal formado cuenta como inexistente, como en el login. */
    private Optional<Account> findByEmail(String email) {
        try {
            return accountRepository.findByEmail(new Email(email));
        } catch (InvalidEmailFormatException malformed) {
            return Optional.empty();
        }
    }
}
