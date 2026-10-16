package identity.application.port.out;

import identity.domain.model.AccountId;
import identity.domain.model.DeviceCredential;

import java.time.Instant;
import java.util.Optional;

/** Persistencia de {@link DeviceCredential}: colección {@code device_credentials}. Nunca el token, solo su hash. */
public interface DeviceCredentialRepositoryPort {

    void insert(DeviceCredential credential);

    /** Por el SHA-256 del token (índice único), solo si no está revocada. */
    Optional<DeviceCredential> findActiveByTokenHash(String tokenHash);

    long countActive(AccountId accountId);

    void markUsed(String credentialId, Instant usedAt);

    /** Escritura condicional: revoca solo si es de {@code accountId} y sigue activa. Devuelve si se aplicó. */
    boolean revokeIfActive(String credentialId, AccountId accountId, Instant revokedAt);

    /** Revoca todas las activas de la cuenta. Devuelve cuántas. */
    long revokeAllActive(AccountId accountId, Instant revokedAt);
}
