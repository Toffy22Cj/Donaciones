package identity.application.service;

import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan B3 §2.2 y ADR-047 P4 (punto 8, parte de {@code identity}): los tres fallos son la misma excepción y cuestan
 * lo mismo, porque {@code matches} se ejecuta también cuando el email no existe.
 */
class AuthenticateAccountPortImplTest {

    private static final String EMAIL = "donor@example.org";
    private static final String PASSWORD = "correct-horse-battery";

    private AccountRepositoryPort accounts;
    private PasswordHasherPort hasher;
    private PasswordHash storedHash;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepositoryPort.class);
        hasher = spy(new BCryptPasswordHasherAdapter());
        storedHash = new BCryptPasswordHasherAdapter().hash(PASSWORD);
    }

    private Account account(AccountStatus status) {
        return Account.reconstitute(new AccountId("acc-1"), new Email(EMAIL), storedHash, status, null, null);
    }

    @Test
    void correctCredentials_returnTheAccountId() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        assertThat(new AuthenticateAccountPortImpl(accounts, hasher).authenticate(EMAIL, PASSWORD)).isEqualTo("acc-1");
    }

    @Test
    void wrongPassword_fails() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        assertThatThrownBy(() -> new AuthenticateAccountPortImpl(accounts, hasher).authenticate(EMAIL, "wrong"))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void unknownEmail_failsWithTheSameException_afterComparingAgainstTheDummyHash() {
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
        AuthenticateAccountPortImpl port = new AuthenticateAccountPortImpl(accounts, hasher);

        assertThatThrownBy(() -> port.authenticate("nobody@example.org", PASSWORD))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), any(PasswordHash.class));
    }

    @Test
    void malformedEmail_isTreatedAsUnknown() {
        AuthenticateAccountPortImpl port = new AuthenticateAccountPortImpl(accounts, hasher);

        assertThatThrownBy(() -> port.authenticate("not-an-email", PASSWORD))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), any(PasswordHash.class));
    }

    @Test
    void inactiveAccount_failsWithTheSameException_evenWithTheRightPassword_andStillComparesThePassword() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.INACTIVE)));
        AuthenticateAccountPortImpl port = new AuthenticateAccountPortImpl(accounts, hasher);

        assertThatThrownBy(() -> port.authenticate(EMAIL, PASSWORD))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), eq(storedHash));
    }

    @Test
    void theExceptionCarriesNoReason() {
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
        AuthenticateAccountPortImpl port = new AuthenticateAccountPortImpl(accounts, hasher);

        assertThatThrownBy(() -> port.authenticate(EMAIL, PASSWORD))
                .hasMessage("Authentication failed")
                .hasNoCause();
    }

    /**
     * Oráculo de tiempo (ADR-047 Q5): la mediana del email inexistente no se distingue de la de la contraseña
     * incorrecta. Margen amplio (×2) para no depender de la carga de la máquina; sin el hash ficticio la diferencia es
     * de dos órdenes de magnitud (BCrypt(12) frente a nada).
     */
    @Test
    void unknownEmailAndWrongPassword_takeComparableTime() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        when(accounts.findByEmail(new Email("nobody@example.org"))).thenReturn(Optional.empty());
        AuthenticateAccountPortImpl port = new AuthenticateAccountPortImpl(accounts, new BCryptPasswordHasherAdapter());

        attempt(port, EMAIL, "wrong");                 // calentamiento
        attempt(port, "nobody@example.org", PASSWORD);

        long wrongPassword = median(port, EMAIL, "wrong");
        long unknownEmail = median(port, "nobody@example.org", PASSWORD);

        assertThat((double) unknownEmail).isBetween(wrongPassword / 2.0, wrongPassword * 2.0);
    }

    private static long median(AuthenticateAccountPortImpl port, String email, String password) {
        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = attempt(port, email, password);
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static long attempt(AuthenticateAccountPortImpl port, String email, String password) {
        long start = System.nanoTime();
        try {
            port.authenticate(email, password);
        } catch (AuthenticationFailedException expected) {
            // esperado
        }
        return System.nanoTime() - start;
    }
}
