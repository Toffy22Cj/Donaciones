package identity.application.service;

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
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Entrar con biometría: el alta guarda solo el hash, y todos los fallos al entrar son la misma excepción. */
class DeviceCredentialServiceTest {

    private static final AccountId ACCOUNT = new AccountId("acc-1");

    private DeviceCredentialRepositoryPort credentials;
    private AccountRepositoryPort accounts;
    private DeviceCredentialService service;

    @BeforeEach
    void setUp() {
        credentials = mock(DeviceCredentialRepositoryPort.class);
        accounts = mock(AccountRepositoryPort.class);
        MongoTransactionRetryHelper retry = mock(MongoTransactionRetryHelper.class);
        when(retry.executeWithRetry(any(Supplier.class))).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        service = new DeviceCredentialService(credentials, accounts, retry);
    }

    private static Account account(AccountStatus status) {
        return Account.reconstitute(ACCOUNT, new Email("ana@example.org"), new PasswordHash("$2a$10$x"), status, null, null);
    }

    private static DeviceCredential active(String token) {
        return DeviceCredential.reconstitute("cred-1", ACCOUNT, DeviceToken.hashOf(token), null, Instant.now(), null, null);
    }

    @Test
    void elAlta_guardaSoloElHash_yDevuelveElTokenUnaVez() {
        DeviceCredentialService.Enrollment e = service.enroll(ACCOUNT, "  Mi teléfono  ");

        ArgumentCaptor<DeviceCredential> saved = ArgumentCaptor.forClass(DeviceCredential.class);
        verify(credentials).insert(saved.capture());
        assertThat(e.token().value()).hasSize(43);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(DeviceToken.hashOf(e.token().value()))
                .isNotEqualTo(e.token().value());
        assertThat(saved.getValue().getCredentialId()).isEqualTo(e.credentialId());
        assertThat(saved.getValue().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getValue().getDeviceName()).isEqualTo("Mi teléfono");
        assertThat(e.token().toString()).doesNotContain(e.token().value());
    }

    @Test
    void conElMaximoDeDispositivos_noDaOtro() {
        when(credentials.countActive(ACCOUNT)).thenReturn((long) DeviceCredentialService.MAX_ACTIVE_PER_ACCOUNT);

        assertThatThrownBy(() -> service.enroll(ACCOUNT, null)).isExactlyInstanceOf(TooManyDeviceCredentialsException.class);
        verify(credentials, never()).insert(any());
    }

    @Test
    void unTokenActivoDeUnaCuentaActiva_entra_yQuedaAnotadoElUso() {
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE));

        assertThat(service.authenticate("tok")).isEqualTo("acc-1");
        verify(credentials).markUsed(eq("cred-1"), any(Instant.class));
    }

    @Test
    void tokenVacioDesconocidoOCuentaInactivaOInexistente_sonElMismoFallo() {
        when(credentials.findActiveByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate(" ")).isExactlyInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> service.authenticate("desconocido")).isExactlyInstanceOf(AuthenticationFailedException.class);

        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.INACTIVE));
        assertThatThrownBy(() -> service.authenticate("tok")).isExactlyInstanceOf(AuthenticationFailedException.class);

        when(accounts.findById(ACCOUNT)).thenThrow(new AccountNotFoundException("x"));
        assertThatThrownBy(() -> service.authenticate("tok")).isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(credentials, never()).markUsed(anyString(), any());
    }

    @Test
    void revocarUnaQueNoEsDeLaCuentaOYaRevocada_esNoEncontrada() {
        when(credentials.revokeIfActive(eq("cred-1"), eq(ACCOUNT), any())).thenReturn(false);

        assertThatThrownBy(() -> service.revoke(ACCOUNT, "cred-1")).isExactlyInstanceOf(DeviceCredentialNotFoundException.class);
    }
}
