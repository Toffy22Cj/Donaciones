package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.GoogleIdTokenVerifierPort;
import identity.application.port.out.GoogleIdTokenVerifierPort.VerifiedGoogleIdentity;
import identity.application.port.out.PasswordHasherPort;
import identity.application.port.out.SecurityNoticeMailPort;
import identity.application.port.out.SecurityNoticeMailPort.SecurityNotice;
import identity.domain.exception.AccountAlreadyLinkedToGoogleException;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.exception.GoogleIdTokenInvalidException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.LegalVersions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * [Plan] Google (ADR-050 D9; plan de Google aprobado en #164, con las tres ampliaciones de Manuel).
 * <ul>
 *   <li>{@link #authenticate}: verificar el {@code idToken} → buscar la cuenta por el {@code sub}, <b>nunca por el
 *       correo</b> → {@code ACTIVE} → correo verificado (E2-3, P-G-1) → aceptaciones (D3, P-G-2). Sin cuenta, cuenta
 *       {@code INACTIVE} o {@code idToken} inválido → el mismo {@link AuthenticationFailedException} que la
 *       contraseña. Google sin responder → 503 (P-G-9).</li>
 *   <li>{@link #link}: con sesión y {@code currentPassword}, comprobada <b>antes</b> que el {@code idToken}
 *       (ampliación 1). Vincula el {@code sub}; si el correo de Google es el de la cuenta, la verifica (P-G-1). Audita
 *       {@code GOOGLE_LINKED} y avisa por correo (ampliación 3).</li>
 *   <li>{@link #unlink}: con sesión y {@code currentPassword} (ampliación 2). Audita {@code GOOGLE_UNLINKED} y avisa.</li>
 * </ul>
 * Los avisos se entregan después de confirmar; nunca llevan el {@code sub} ni el {@code idToken}.
 */
@Service
public class GoogleSignInService {

    private final AccountRepositoryPort accounts;
    private final GoogleIdTokenVerifierPort verifier;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final SecurityNoticeMailPort notices;
    private final ObjectProvider<LegalVersions> legalVersions;
    private final Clock clock;

    public GoogleSignInService(AccountRepositoryPort accounts, GoogleIdTokenVerifierPort verifier,
                               PasswordHasherPort passwordHasher, AuditLogPort auditLogPort,
                               MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors,
                               SecurityNoticeMailPort notices, ObjectProvider<LegalVersions> legalVersions,
                               ObjectProvider<Clock> clock) {
        this.accounts = accounts;
        this.verifier = verifier;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.notices = notices;
        this.legalVersions = legalVersions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** {@code POST /auth/google}. Devuelve lo mismo que el login con contraseña, para emitir con la regla de E1-2. */
    public AuthenticatedAccount authenticate(String idToken, AcceptedLegalVersions accepted) {
        VerifiedGoogleIdentity identity;
        try {
            identity = verifier.verify(idToken);
        } catch (GoogleIdTokenInvalidException invalid) {
            throw new AuthenticationFailedException();
        }
        Account account = accounts.findByGoogleSubject(identity.subject())
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(AuthenticationFailedException::new);
        if (!account.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        LoginAcceptances.require(accounts, legalVersions.getObject(), clock, account, accepted);
        return new AuthenticatedAccount(account.getAccountId().value(), account.getCredentialsNotBefore());
    }

    /** {@code POST /account/google/link}. La misma vinculación repetida no cambia nada ni audita (P-G-4). */
    public void link(AuthorizationPrincipal principal, String currentPassword, String idToken) {
        Account account = requireCurrentPassword(principal, currentPassword);
        VerifiedGoogleIdentity identity = verifier.verify(idToken);
        if (identity.subject().equals(account.getGoogleSubject())) {
            return;
        }
        if (account.getGoogleSubject() != null) {
            throw new AccountAlreadyLinkedToGoogleException("The account is already linked to another Google account");
        }
        AuditActor actor = auditActors.toAuditActor(principal);
        Instant linkedAt = retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            if (!accounts.linkGoogleIfUnlinked(account.getAccountId(), identity.subject())) {
                // otro vínculo concurrente, o la cuenta dejó de estar activa
                throw new AccountAlreadyLinkedToGoogleException("The account could not be linked");
            }
            auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor,
                    account.getAccountId(), null, AuditAction.GOOGLE_LINKED, Map.of()));
            // P-G-1: el correo verificado de Google prueba el de la cuenta solo si es el mismo
            if (identity.email() != null && identity.email().equals(account.getEmail().value())
                    && accounts.markEmailVerifiedIfUnverified(account.getAccountId(), now)) {
                auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor,
                        account.getAccountId(), null, AuditAction.EMAIL_VERIFIED, Map.of("via", "GOOGLE_LINK")));
            }
            return now;
        });
        notify(account, SecurityNotice.Kind.GOOGLE_LINKED, linkedAt);
    }

    /** {@code DELETE /account/google/link}. Sin vínculo, nada cambia: ni auditoría ni aviso. */
    public void unlink(AuthorizationPrincipal principal, String currentPassword) {
        Account account = requireCurrentPassword(principal, currentPassword);
        if (account.getGoogleSubject() == null) {
            return;
        }
        AuditActor actor = auditActors.toAuditActor(principal);
        Optional<Instant> unlinkedAt = retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            if (!accounts.unlinkGoogleIfLinked(account.getAccountId(), account.getGoogleSubject())) {
                return Optional.<Instant>empty();
            }
            auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor,
                    account.getAccountId(), null, AuditAction.GOOGLE_UNLINKED, Map.of()));
            return Optional.of(now);
        });
        unlinkedAt.ifPresent(at -> notify(account, SecurityNotice.Kind.GOOGLE_UNLINKED, at));
    }

    /** Ampliación 1 de D9: la misma respuesta que la desactivación (400 {@code CurrentPasswordIncorrect}). */
    private Account requireCurrentPassword(AuthorizationPrincipal principal, String currentPassword) {
        Account account = accounts.findById(new AccountId(principal.accountId()));
        if (currentPassword == null || !passwordHasher.matches(currentPassword, account.getPasswordHash())) {
            throw new CurrentPasswordIncorrectException("Current password does not match");
        }
        return account;
    }

    private void notify(Account account, SecurityNotice.Kind kind, Instant at) {
        notices.dispatch(new SecurityNotice(account.getAccountId().value(), account.getEmail().value(), kind, at));
    }
}
