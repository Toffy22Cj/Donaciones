package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.DeviceCredentialRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.DeviceCredentialNotFoundException;
import identity.domain.exception.TooManyDeviceCredentialsException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.DeviceCredential;
import identity.domain.model.DeviceToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrar con biometría. El teléfono comprueba la huella o el rostro y envía el token de su dispositivo; aquí solo se
 * guarda el SHA-256 del token, nunca la contraseña.
 * <ul>
 *   <li>alta: con sesión iniciada, devuelve el token en claro una única vez;</li>
 *   <li>entrar: token inexistente, revocado, caducado ({@link #MAX_AGE}), anterior al último cambio de contraseña
 *       ({@code credentialsNotBefore}, ADR-050 D4) o de una cuenta {@code INACTIVE} → la misma
 *       {@link AuthenticationFailedException} que el login con contraseña;</li>
 *   <li>revocar: solo las de la propia cuenta; cambiar la contraseña las revoca todas
 *       ({@link ChangeCredentialsService}). Además, cualquier camino que fije {@code credentialsNotBefore} (cambiar,
 *       cambiar la propia o recuperar la contraseña) deja sin efecto las credenciales anteriores.</li>
 * </ul>
 */
@Service
public class DeviceCredentialService {

    /** Máximo de dispositivos con biometría activos por cuenta. */
    public static final int MAX_ACTIVE_PER_ACCOUNT = 10;

    /**
     * Caducidad de una credencial desde su alta (auditoría móvil del 2026-10-29, S-01 opción (b)): pasado este plazo
     * hay que volver a entrar con la contraseña y activar la biometría otra vez.
     */
    public static final Duration MAX_AGE = Duration.ofDays(30);

    /** El alta: el token en claro solo existe aquí y en la respuesta HTTP. */
    public record Enrollment(String credentialId, DeviceToken token) {}

    private final DeviceCredentialRepositoryPort credentials;
    private final AccountRepositoryPort accounts;
    private final MongoTransactionRetryHelper retryHelper;
    private final Clock clock;

    @Autowired
    public DeviceCredentialService(DeviceCredentialRepositoryPort credentials, AccountRepositoryPort accounts,
                                   MongoTransactionRetryHelper retryHelper, ObjectProvider<Clock> clock) {
        this(credentials, accounts, retryHelper, clock.getIfAvailable(Clock::systemUTC));
    }

    DeviceCredentialService(DeviceCredentialRepositoryPort credentials, AccountRepositoryPort accounts,
                            MongoTransactionRetryHelper retryHelper, Clock clock) {
        this.credentials = credentials;
        this.accounts = accounts;
        this.retryHelper = retryHelper;
        this.clock = clock;
    }

    public Enrollment enroll(AccountId accountId, String deviceName) {
        Objects.requireNonNull(accountId, "accountId");
        return retryHelper.executeWithRetry(() -> {
            if (credentials.countActive(accountId) >= MAX_ACTIVE_PER_ACCOUNT) {
                throw new TooManyDeviceCredentialsException("Too many active device credentials");
            }
            DeviceToken token = DeviceToken.generate();
            String credentialId = UlidCreator.getUlid().toString();
            // El alta exige una sesión válida, es decir, posterior al último cambio de contraseña. Como
            // credentialsNotBefore se redondea al segundo siguiente (ADR-050 D4), la credencial vale desde ese
            // instante: así una activada justo después de cambiar la contraseña no nace ya invalidada.
            Instant now = clock.instant();
            Instant notBefore = activeAccount(accountId).map(Account::getCredentialsNotBefore).orElse(null);
            Instant validFrom = notBefore != null && notBefore.isAfter(now) ? notBefore : now;
            credentials.insert(DeviceCredential.issue(credentialId, accountId, token.hash(), deviceName, validFrom));
            return new Enrollment(credentialId, token);
        });
    }

    /**
     * Devuelve el {@code accountId} si el token es de una credencial activa, no caducada, posterior al último cambio
     * de contraseña y de una cuenta {@code ACTIVE}.
     */
    public String authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationFailedException();
        }
        Instant now = clock.instant();
        DeviceCredential credential = credentials.findActiveByTokenHash(DeviceToken.hashOf(token.strip()))
                .orElseThrow(AuthenticationFailedException::new);
        if (!credential.getCreatedAt().plus(MAX_AGE).isAfter(now)) {
            throw new AuthenticationFailedException();
        }
        Account account = activeAccount(credential.getAccountId()).orElseThrow(AuthenticationFailedException::new);
        Instant notBefore = account.getCredentialsNotBefore();
        if (notBefore != null && credential.getCreatedAt().isBefore(notBefore)) {
            throw new AuthenticationFailedException();
        }
        credentials.markUsed(credential.getCredentialId(), now);
        return credential.getAccountId().value();
    }

    public void revoke(AccountId accountId, String credentialId) {
        if (credentialId == null
                || !retryHelper.executeWithRetry(() -> credentials.revokeIfActive(credentialId, accountId, clock.instant()))) {
            throw new DeviceCredentialNotFoundException("Device credential not found");
        }
    }

    /** Todas las de la cuenta: al cambiar la contraseña. */
    public long revokeAll(AccountId accountId) {
        return retryHelper.executeWithRetry(() -> credentials.revokeAllActive(accountId, clock.instant()));
    }

    private Optional<Account> activeAccount(AccountId accountId) {
        try {
            return Optional.ofNullable(accounts.findById(accountId)).filter(a -> a.getStatus() == AccountStatus.ACTIVE);
        } catch (AccountNotFoundException missing) {
            return Optional.empty();
        }
    }
}
