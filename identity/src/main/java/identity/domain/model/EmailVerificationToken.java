package identity.domain.model;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * [Plan] Token de verificación del correo (ADR-050 Enmienda 2, E2-2), con el patrón de la recuperación (D8,
 * {@link PasswordResetToken}): solo se guarda el hash del secreto; el secreto vive en memoria hasta que se entrega el
 * correo. {@code id} es un ULID interno, el único dato del token que puede aparecer en un log.
 */
@Getter
public class EmailVerificationToken {

    /** Validez del token (Q3: 24 h). */
    public static final Duration VALIDITY = Duration.ofHours(24);
    /** Enfriamiento entre dos reenvíos de la misma cuenta (E2-2). */
    public static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final String id;
    private final String tokenHash;
    private final AccountId accountId;
    private final EmailVerificationTokenStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant usedAt;

    private EmailVerificationToken(String id, String tokenHash, AccountId accountId, EmailVerificationTokenStatus status,
                                   Instant createdAt, Instant expiresAt, Instant usedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.usedAt = usedAt;
    }

    /** Nuevo token {@code PENDING}, con {@code expiresAt = now + 24 h}. Recibe el hash, nunca el secreto. */
    public static EmailVerificationToken issue(String id, AccountId accountId, String tokenHash, Instant now) {
        return new EmailVerificationToken(id, tokenHash, accountId, EmailVerificationTokenStatus.PENDING, now,
                now.plus(VALIDITY), null);
    }

    /** Uso exclusivo de adaptadores de persistencia. */
    public static EmailVerificationToken reconstitute(String id, String tokenHash, AccountId accountId,
                                                      EmailVerificationTokenStatus status, Instant createdAt,
                                                      Instant expiresAt, Instant usedAt) {
        return new EmailVerificationToken(id, tokenHash, accountId, status, createdAt, expiresAt, usedAt);
    }

    /** {@code PENDING} y sin caducar: {@code now < expiresAt}. */
    public boolean isUsableAt(Instant now) {
        return status == EmailVerificationTokenStatus.PENDING && now.isBefore(expiresAt);
    }

    /** {@code PENDING} y pedido hace menos de 5 minutos. */
    public boolean isInCooldownAt(Instant now) {
        return status == EmailVerificationTokenStatus.PENDING && createdAt.isAfter(now.minus(COOLDOWN));
    }

    @Override
    public String toString() {
        return "EmailVerificationToken[id=" + id + ", status=" + status + "]";
    }
}
