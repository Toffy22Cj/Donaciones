package identity.application.port.out;

import identity.domain.model.AccountId;
import identity.domain.model.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;

/** Persistencia de los tokens de recuperación (ADR-050 D8; plan R-4 §4). Solo el hash del secreto. */
public interface PasswordResetTokenRepositoryPort {

    /**
     * @throws org.springframework.dao.DuplicateKeyException si la cuenta ya tiene un token {@code PENDING} (índice único
     *         parcial): dos peticiones simultáneas, gana una
     */
    void insert(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    Optional<PasswordResetToken> findPendingByAccount(AccountId accountId);

    /** {@code PENDING → SUPERSEDED} de los tokens de la cuenta. */
    long supersedePendingFor(AccountId accountId);

    /** Escritura condicional {@code PENDING → USED}: {@code false} si otro la ganó. */
    boolean markUsedIfPending(String id, Instant usedAt);
}
