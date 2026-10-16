package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.DeviceCredentialRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.DeviceCredentialNotFoundException;
import identity.domain.exception.TooManyDeviceCredentialsException;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.DeviceCredential;
import identity.domain.model.DeviceToken;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrar con biometría. El teléfono comprueba la huella o el rostro y envía el token de su dispositivo; aquí solo se
 * guarda el SHA-256 del token, nunca la contraseña.
 * <ul>
 *   <li>alta: con sesión iniciada, devuelve el token en claro una única vez;</li>
 *   <li>entrar: token inexistente, revocado o de una cuenta {@code INACTIVE} → la misma
 *       {@link AuthenticationFailedException} que el login con contraseña;</li>
 *   <li>revocar: solo las de la propia cuenta; cambiar la contraseña las revoca todas
 *       ({@link ChangeCredentialsService}).</li>
 * </ul>
 */
@Service
public class DeviceCredentialService {

    /** Máximo de dispositivos con biometría activos por cuenta. */
    public static final int MAX_ACTIVE_PER_ACCOUNT = 10;

    /** El alta: el token en claro solo existe aquí y en la respuesta HTTP. */
    public record Enrollment(String credentialId, DeviceToken token) {}

    private final DeviceCredentialRepositoryPort credentials;
    private final AccountRepositoryPort accounts;
    private final MongoTransactionRetryHelper retryHelper;

    public DeviceCredentialService(DeviceCredentialRepositoryPort credentials, AccountRepositoryPort accounts,
                                   MongoTransactionRetryHelper retryHelper) {
        this.credentials = credentials;
        this.accounts = accounts;
        this.retryHelper = retryHelper;
    }

    public Enrollment enroll(AccountId accountId, String deviceName) {
        Objects.requireNonNull(accountId, "accountId");
        return retryHelper.executeWithRetry(() -> {
            if (credentials.countActive(accountId) >= MAX_ACTIVE_PER_ACCOUNT) {
                throw new TooManyDeviceCredentialsException("Too many active device credentials");
            }
            DeviceToken token = DeviceToken.generate();
            String credentialId = UlidCreator.getUlid().toString();
            credentials.insert(DeviceCredential.issue(credentialId, accountId, token.hash(), deviceName, Instant.now()));
            return new Enrollment(credentialId, token);
        });
    }

    /** Devuelve el {@code accountId} si el token es de una credencial activa y de una cuenta {@code ACTIVE}. */
    public String authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationFailedException();
        }
        Optional<DeviceCredential> credential = credentials.findActiveByTokenHash(DeviceToken.hashOf(token.strip()));
        if (credential.isEmpty() || !isActiveAccount(credential.get().getAccountId())) {
            throw new AuthenticationFailedException();
        }
        credentials.markUsed(credential.get().getCredentialId(), Instant.now());
        return credential.get().getAccountId().value();
    }

    public void revoke(AccountId accountId, String credentialId) {
        if (credentialId == null
                || !retryHelper.executeWithRetry(() -> credentials.revokeIfActive(credentialId, accountId, Instant.now()))) {
            throw new DeviceCredentialNotFoundException("Device credential not found");
        }
    }

    /** Todas las de la cuenta: al cambiar la contraseña. */
    public long revokeAll(AccountId accountId) {
        return retryHelper.executeWithRetry(() -> credentials.revokeAllActive(accountId, Instant.now()));
    }

    private boolean isActiveAccount(AccountId accountId) {
        try {
            return accounts.findById(accountId).getStatus() == AccountStatus.ACTIVE;
        } catch (AccountNotFoundException missing) {
            return false;
        }
    }
}
