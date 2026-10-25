package identity.domain.model;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Token de recuperación de contraseña (ADR-050 D8; plan R-4 §4). Solo se guarda el hash del secreto (patrón de ADR-049
 * D2, {@link InvitationToken}); el secreto vive en memoria hasta que se entrega el correo. {@code id} es un ULID interno,
 * el único dato del token que puede aparecer en un log.
 */
@Getter
public class PasswordResetToken {

    /** Validez del token (D8). */
    public static final Duration VALIDITY = Duration.ofMinutes(30);
    /** Enfriamiento entre dos peticiones de la misma cuenta (D8). */
    public static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final String id;
    private final String tokenHash;
    private final AccountId accountId;
    private final PasswordResetTokenStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant usedAt;

    private PasswordResetToken(String id, String tokenHash, AccountId accountId, PasswordResetTokenStatus status,
                               Instant createdAt, Instant expiresAt, Instant usedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.usedAt = usedAt;
    }

    /** Nuevo token {@code PENDING}, con {@code expiresAt = now + 30 min}. Recibe el hash, nunca el secreto. */
    public static PasswordResetToken issue(String id, AccountId accountId, String tokenHash, Instant now) {
        return new PasswordResetToken(id, tokenHash, accountId, PasswordResetTokenStatus.PENDING, now,
                now.plus(VALIDITY), null);
    }

    /** Uso exclusivo de adaptadores de persistencia. */
    public static PasswordResetToken reconstitute(String id, String tokenHash, AccountId accountId,
                                                 PasswordResetTokenStatus status, Instant createdAt,
                                                 Instant expiresAt, Instant usedAt) {
        return new PasswordResetToken(id, tokenHash, accountId, status, createdAt, expiresAt, usedAt);
    }

    /** {@code PENDING} y sin caducar: {@code now < expiresAt}. */
    public boolean isUsableAt(Instant now) {
        return status == PasswordResetTokenStatus.PENDING && now.isBefore(expiresAt);
    }

    /** {@code PENDING} y pedido hace menos de 5 minutos: {@code createdAt > now - 5 min}. */
    public boolean isInCooldownAt(Instant now) {
        return status == PasswordResetTokenStatus.PENDING && createdAt.isAfter(now.minus(COOLDOWN));
    }

    @Override
    public String toString() {
        return "PasswordResetToken[id=" + id + ", status=" + status + "]";
    }
}
