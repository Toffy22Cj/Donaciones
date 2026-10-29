package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.application.port.out.PasswordResetTokenRepositoryPort;
import identity.application.port.out.SecurityNoticeMailPort;
import identity.application.port.out.SecurityNoticeMailPort.SecurityNotice;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.exception.PasswordResetTokenInvalidException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.InvitationToken;
import identity.domain.model.PasswordHash;
import identity.domain.model.PasswordResetToken;
import identity.domain.model.PlainPassword;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

@Service
public class ChangeCredentialsService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final PasswordResetTokenRepositoryPort resetTokens;
    private final EmailVerificationService verifications;
    private final SecurityNoticeMailPort notices;
    private final Clock clock;

    public ChangeCredentialsService(AccountRepositoryPort accountRepository, 
                                  PasswordHasherPort passwordHasher, 
                                  AuditLogPort auditLogPort,
                                  MongoTransactionRetryHelper retryHelper,
                                  AuthorizationAuditActorMapper auditActors,
                                  PasswordResetTokenRepositoryPort resetTokens,
                                  EmailVerificationService verifications,
                                  SecurityNoticeMailPort notices,
                                  ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.resetTokens = resetTokens;
        this.verifications = verifications;
        this.notices = notices;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Sin contraseña actual (la reutilizará la recuperación, R-4). También revoca los tokens anteriores (ADR-050 D4;
     * plan R-3 + R-2, P-R3-2): ningún camino cambia la contraseña sin revocar.
     */
    public void changeCredentials(AuditActor actor, AccountId accountId, String newPlainPassword) {
        Objects.requireNonNull(actor, "actor must not be null");
        // H-P2-1: la misma política que al crear la cuenta
        PlainPassword password = new PlainPassword(newPlainPassword);

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            applyChange(actor, account, password, Collections.emptyMap());
        });
    }

    /**
     * ADR-050 D5, en una transacción y en este orden (plan R-3 + R-2, P-R3-1 (a)): 1. la contraseña actual con BCrypt;
     * 2. la política de la nueva; 3. guardar el hash, fijar {@code credentialsNotBefore} y auditar sin contraseñas.
     *
     * La cuenta es la del principal autenticado, que también es el actor de la auditoría.
     *
     * ADR-050 Enmienda 2, E2-4: después de confirmar, entrega el aviso {@code PASSWORD_CHANGED}; un cambio rechazado no
     * avisa.
     *
     * @return el {@code credentialsNotBefore} confirmado, que es el {@code iat} del token nuevo
     * @throws CurrentPasswordIncorrectException si la actual no coincide; nada cambia
     */
    public Instant changeOwnCredentials(AuthorizationPrincipal principal, String currentPlainPassword, String newPlainPassword) {
        AuditActor self = auditActors.toAuditActor(principal);
        AccountId accountId = new AccountId(principal.accountId());

        Changed changed = retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            if (currentPlainPassword == null || !passwordHasher.matches(currentPlainPassword, account.getPasswordHash())) {
                throw new CurrentPasswordIncorrectException("Current password does not match");
            }
            PlainPassword password = new PlainPassword(newPlainPassword);
            return new Changed(account, applyChange(self, account, password, Collections.emptyMap()));
        });
        notify(changed, SecurityNotice.Kind.PASSWORD_CHANGED);
        return changed.account().getCredentialsNotBefore();
    }

    /** La cuenta tal como quedó y el instante del cambio, para el aviso que se entrega después de confirmar. */
    private record Changed(Account account, Instant at) {}

    private void notify(Changed changed, SecurityNotice.Kind kind) {
        notices.dispatch(new SecurityNotice(changed.account().getAccountId().value(), changed.account().getEmail().value(),
                kind, changed.at()));
    }

    /**
     * ADR-050 D8 (plan R-4 §4.2): confirmación de la recuperación. La política de la nueva va <b>antes</b> de leer el
     * token, para que una contraseña corta nunca lo consuma (P-R4-2). Después, en una sola transacción y sin llamar a
     * {@link #changeCredentials}, que se anidaría: el token por su hash, utilizable; la cuenta {@code ACTIVE}; {@code
     * PENDING → USED} condicional; el cambio, que fija {@code credentialsNotBefore} (D4); y la auditoría {@code
     * CREDENTIALS_CHANGED} con {@code {via: "PASSWORD_RESET"}}.
     * <p>
     * El actor es {@code AccountAuditActor} de la cuenta del token: quien confirma demostró controlar su correo (ADR-050
     * Enmienda 1, parte 2, que enmienda ADR-038 E1). Cualquier condición no cumplida es la misma {@link
     * PasswordResetTokenInvalidException} y la transacción se revierte entera.
     * <p>
     * ADR-050 Enmienda 2, E2-2: en la misma transacción, y después de guardar la cuenta, una cuenta sin verificar queda
     * verificada (P-E2V-8). E2-4: después de confirmar, se entrega el aviso {@code PASSWORD_RESET}.
     */
    public void resetCredentials(String token, String newPlainPassword) {
        PlainPassword password = new PlainPassword(newPlainPassword);
        if (token == null || token.isBlank()) {
            throw new PasswordResetTokenInvalidException();
        }
        String tokenHash = InvitationToken.hashOf(token);
        // BCrypt fuera de la transacción: con él dentro, la confirmación ganadora la mantiene abierta lo que tarda el
        // hash, y la perdedora agota sus reintentos de WriteConflict en markUsedIfPending (500) en lugar de releer USED
        PasswordHash newPasswordHash = passwordHasher.hash(password.value());

        Changed changed = retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            PasswordResetToken resetToken = resetTokens.findByTokenHash(tokenHash)
                    .filter(t -> t.isUsableAt(now))
                    .orElseThrow(PasswordResetTokenInvalidException::new);
            Account account = accountRepository.findById(resetToken.getAccountId());
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new PasswordResetTokenInvalidException();
            }
            if (!resetTokens.markUsedIfPending(resetToken.getId(), now)) {
                throw new PasswordResetTokenInvalidException();
            }
            Instant changedAt = applyChange(new AuditActor.AccountAuditActor(account.getAccountId()), account,
                    newPasswordHash, Map.<String, Object>of("via", "PASSWORD_RESET"));
            // después del save, que reemplaza el documento: la escritura condicional no se pisa
            verifications.verifyWithinPasswordReset(account.getAccountId(), changedAt);
            return new Changed(account, changedAt);
        });
        notify(changed, SecurityNotice.Kind.PASSWORD_RESET);
    }

    private Instant applyChange(AuditActor actor, Account account, PlainPassword password, Map<String, Object> summary) {
        return applyChange(actor, account, passwordHasher.hash(password.value()), summary);
    }

    /** @return el instante del cambio */
    private Instant applyChange(AuditActor actor, Account account, PasswordHash newPasswordHash, Map<String, Object> summary) {
        Instant now = clock.instant();
        account.changeCredentials(newPasswordHash, now);

        accountRepository.save(account);

        AuditLogEntry auditLog = AuditLogEntry.record(
                UlidCreator.getUlid().toString(),
                now,
                actor,
                account.getAccountId(),
                null,
                AuditAction.CREDENTIALS_CHANGED,
                summary
        );
        auditLogPort.record(auditLog);
        return now;
    }
}
