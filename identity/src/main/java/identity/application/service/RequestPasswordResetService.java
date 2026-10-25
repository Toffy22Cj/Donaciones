package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordResetMailPort;
import identity.application.port.out.PasswordResetMailPort.PasswordResetMail;
import identity.application.port.out.PasswordResetTokenRepositoryPort;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.model.Account;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.InvitationToken;
import identity.domain.model.PasswordResetToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Petición de recuperación de contraseña (ADR-050 D8, Q5 (a); plan R-4 §4.1). Pase lo que pase, la ruta responde lo
 * mismo: aquí no se lanza nada por una cuenta inexistente, inactiva, en enfriamiento o perdida en una carrera.
 * <ul>
 *   <li>Correo mal formado, inexistente o de una cuenta {@code INACTIVE} → no se escribe nada ni se envía nada.</li>
 *   <li>Una {@code PENDING} de menos de 5 minutos → enfriamiento: nada nuevo.</li>
 *   <li>Si no, en una transacción: el {@code PENDING} anterior pasa a {@code SUPERSEDED} y se guarda uno nuevo (solo su
 *       hash). El secreto se genera <b>dentro</b> del intento y se devuelve el del intento que confirmó.</li>
 *   <li>El correo se entrega <b>después</b> de que la transacción confirme, una sola vez y sin reintentos.</li>
 * </ul>
 * Dos peticiones simultáneas de la misma cuenta: el índice único parcial ({@code PENDING} por cuenta) deja ganar a una;
 * la otra recibe {@link DuplicateKeyException}, que cuenta como enfriamiento. Sin auditoría: el ADR no la pide.
 */
@Service
public class RequestPasswordResetService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordResetTokenRepositoryPort resetTokens;
    private final PasswordResetMailPort mail;
    private final MongoTransactionRetryHelper retryHelper;
    private final Clock clock;

    public RequestPasswordResetService(AccountRepositoryPort accountRepository,
                                       PasswordResetTokenRepositoryPort resetTokens,
                                       PasswordResetMailPort mail,
                                       MongoTransactionRetryHelper retryHelper,
                                       ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.resetTokens = resetTokens;
        this.mail = mail;
        this.retryHelper = retryHelper;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    private record Issued(PasswordResetToken token, String secret) {}

    public void request(String email) {
        Optional<Account> found = findByEmail(email);
        if (found.isEmpty() || found.get().getStatus() == AccountStatus.INACTIVE) {
            return;
        }
        Account account = found.get();

        Optional<Issued> issued;
        try {
            issued = retryHelper.executeWithRetry(() -> {
                Instant now = clock.instant();
                Optional<PasswordResetToken> pending = resetTokens.findPendingByAccount(account.getAccountId());
                if (pending.isPresent() && pending.get().isInCooldownAt(now)) {
                    return Optional.<Issued>empty();
                }
                resetTokens.supersedePendingFor(account.getAccountId());
                InvitationToken secret = InvitationToken.generate();
                PasswordResetToken token = PasswordResetToken.issue(UlidCreator.getUlid().toString(),
                        account.getAccountId(), secret.hash(), now);
                resetTokens.insert(token);
                return Optional.of(new Issued(token, secret.value()));
            });
        } catch (DuplicateKeyException concurrentWinner) {
            return;
        }

        issued.ifPresent(i -> mail.dispatch(new PasswordResetMail(i.token().getId(), account.getEmail().value(),
                i.secret(), i.token().getExpiresAt())));
    }

    /** Un correo mal formado cuenta como inexistente, como en el login (P-R4-6). */
    private Optional<Account> findByEmail(String email) {
        try {
            return accountRepository.findByEmail(new Email(email));
        } catch (InvalidEmailFormatException malformed) {
            return Optional.empty();
        }
    }
}
