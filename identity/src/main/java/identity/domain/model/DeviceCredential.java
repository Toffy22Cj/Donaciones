package identity.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Credencial de un dispositivo para entrar con biometría. La huella o el rostro solo se comprueban en el teléfono, que
 * después envía el token del dispositivo; aquí se guarda solo su hash. Sirve hasta que se revoca: la revoca el usuario
 * (al desactivar la biometría) o el sistema (al cambiar la contraseña). Una cuenta {@code INACTIVE} no entra con ella.
 */
public final class DeviceCredential {

    public static final int MAX_DEVICE_NAME = 60;

    private final String credentialId;
    private final AccountId accountId;
    private final String tokenHash;
    private final String deviceName;
    private final Instant createdAt;
    private final Instant lastUsedAt;
    private final Instant revokedAt;

    private DeviceCredential(String credentialId, AccountId accountId, String tokenHash, String deviceName,
                             Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        this.credentialId = Objects.requireNonNull(credentialId, "credentialId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.deviceName = deviceName;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
    }

    public static DeviceCredential issue(String credentialId, AccountId accountId, String tokenHash, String deviceName,
                                         Instant now) {
        return new DeviceCredential(credentialId, accountId, tokenHash, normalizeName(deviceName), now, null, null);
    }

    public static DeviceCredential reconstitute(String credentialId, AccountId accountId, String tokenHash,
                                                String deviceName, Instant createdAt, Instant lastUsedAt,
                                                Instant revokedAt) {
        return new DeviceCredential(credentialId, accountId, tokenHash, deviceName, createdAt, lastUsedAt, revokedAt);
    }

    /** Nombre opcional para que el usuario reconozca el dispositivo: sin espacios a los lados y como mucho 60 caracteres. */
    static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.strip();
        return n.length() <= MAX_DEVICE_NAME ? n : n.substring(0, MAX_DEVICE_NAME);
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public String getCredentialId() { return credentialId; }
    public AccountId getAccountId() { return accountId; }
    public String getTokenHash() { return tokenHash; }
    public String getDeviceName() { return deviceName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    @Override
    public String toString() {
        return "DeviceCredential[" + credentialId + "]";
    }
}
