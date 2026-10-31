package identity.application.port.out;

import identity.domain.model.AccountId;
import identity.domain.model.EmailVerificationToken;

import java.time.Instant;
import java.util.Optional;

/** [Plan] Persistencia de los tokens de verificación del correo (ADR-050 Enmienda 2, E2-2). Solo el hash del secreto. */
public interface EmailVerificationTokenRepositoryPort {

    /**
     * @throws org.springframework.dao.DuplicateKeyException si la cuenta ya tiene un token {@code PENDING} (índice único
     *         parcial): dos peticiones simultáneas, gana una
     */
    void insert(EmailVerificationToken token);

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    Optional<EmailVerificationToken> findPendingByAccount(AccountId accountId);

    /** {@code PENDING → SUPERSEDED} de los tokens de la cuenta. */
    long supersedePendingFor(AccountId accountId);

    /** Escritura condicional {@code PENDING → USED}: {@code false} si otro la ganó. */
    boolean markUsedIfPending(String id, Instant usedAt);
}
