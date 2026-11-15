package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import identity.domain.model.LegalAcceptance;
import identity.domain.model.LegalVersions;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String V = "v1-2026-10";
    private static final LegalVersions CURRENT = new LegalVersions(V, V, V);

    private DeviceCredentialRepositoryPort credentials;
    private AccountRepositoryPort accounts;
    private DeviceCredentialService service;

    @BeforeEach
    void setUp() {
        credentials = mock(DeviceCredentialRepositoryPort.class);
        accounts = mock(AccountRepositoryPort.class);
        MongoTransactionRetryHelper retry = mock(MongoTransactionRetryHelper.class);
        when(retry.executeWithRetry(any(Supplier.class))).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        service = new DeviceCredentialService(credentials, accounts, retry, () -> CURRENT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Account account(AccountStatus status) {
        return account(status, null);
    }

    /** Verificada y con las versiones vigentes aceptadas: lo que pide cualquier inicio de sesión. */
    private static Account account(AccountStatus status, Instant credentialsNotBefore) {
        return account(status, credentialsNotBefore, NOW.minus(Duration.ofDays(10)), new LegalAcceptance(V, NOW));
    }

    private static Account account(AccountStatus status, Instant credentialsNotBefore, Instant emailVerifiedAt,
                                   LegalAcceptance accepted) {
        return Account.reconstitute(ACCOUNT, new Email("ana@example.org"), new PasswordHash("$2a$10$x"), status, null, null,
                credentialsNotBefore, null, null, accepted, accepted, emailVerifiedAt);
    }

    private static DeviceCredential active(String token) {
        return issuedAt(token, NOW.minus(Duration.ofDays(1)));
    }

    private static DeviceCredential issuedAt(String token, Instant createdAt) {
        return DeviceCredential.reconstitute("cred-1", ACCOUNT, DeviceToken.hashOf(token), null, createdAt, null, null);
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
    void activadaJustoDespuesDeCambiarLaContrasena_valeDesdeCredentialsNotBefore() {
        // credentialsNotBefore se redondea al segundo siguiente (ADR-050 D4): la credencial no nace invalidada.
        Instant notBefore = NOW.plusMillis(700);
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE, notBefore));

        service.enroll(ACCOUNT, null);

        ArgumentCaptor<DeviceCredential> saved = ArgumentCaptor.forClass(DeviceCredential.class);
        verify(credentials).insert(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(notBefore);
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

        assertThat(service.authenticate("tok", AcceptedLegalVersions.NONE).accountId()).isEqualTo("acc-1");
        verify(credentials).markUsed(eq("cred-1"), any(Instant.class));
    }

    @Test
    void tokenVacioDesconocidoOCuentaInactivaOInexistente_sonElMismoFallo() {
        when(credentials.findActiveByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticate(" ", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> service.authenticate("desconocido", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);

        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.INACTIVE));
        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);

        when(accounts.findById(ACCOUNT)).thenThrow(new AccountNotFoundException("x"));
        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(credentials, never()).markUsed(anyString(), any());
    }

    @Test
    void unaCredencialDeMasDe30Dias_caduca_yEsElMismoFallo() {
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok")))
                .thenReturn(Optional.of(issuedAt("tok", NOW.minus(DeviceCredentialService.MAX_AGE))));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE));

        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(credentials, never()).markUsed(anyString(), any());
    }

    @Test
    void unaCredencialAnteriorAlUltimoCambioDeContrasena_noEntra() {
        // Cambiar la propia contraseña o recuperarla fija credentialsNotBefore (ADR-050 D4): la huella anterior no vale.
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE, NOW.minus(Duration.ofHours(1))));

        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE)).isExactlyInstanceOf(AuthenticationFailedException.class);

        // Activada después del cambio, sí entra.
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE, NOW.minus(Duration.ofDays(2))));
        assertThat(service.authenticate("tok", AcceptedLegalVersions.NONE).accountId()).isEqualTo("acc-1");
    }

    @Test
    void devuelveElCredentialsNotBefore_paraEmitirElTokenComoElLogin() {
        // ADR-050 Enmienda 1, E1-2: el iat del token es max(ahora, credentialsNotBefore), igual que con contraseña.
        Instant notBefore = NOW.minus(Duration.ofDays(2));
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE, notBefore));

        assertThat(service.authenticate("tok", AcceptedLegalVersions.NONE).credentialsNotBefore()).isEqualTo(notBefore);
    }

    @Test
    void conElCorreoSinVerificar_esEmailNotVerified_comoElLogin_yNoAnotaElUso() {
        // ADR-050 Enmienda 2, E2-3: va después de la credencial y el estado, antes de las aceptaciones.
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(account(AccountStatus.ACTIVE, null, null, null));

        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(EmailNotVerifiedException.class);
        verify(credentials, never()).markUsed(anyString(), any());
    }

    @Test
    void conVersionesLegalesPendientes_esAcceptanceRequired_yConEllasEntraYLasGuarda() {
        // ADR-050 D3: la misma regla que el login con contraseña y con Google (LoginAcceptances).
        Account pending = account(AccountStatus.ACTIVE, null, NOW.minus(Duration.ofDays(10)), new LegalAcceptance("v0", NOW));
        when(credentials.findActiveByTokenHash(DeviceToken.hashOf("tok"))).thenReturn(Optional.of(active("tok")));
        when(accounts.findById(ACCOUNT)).thenReturn(pending);

        assertThatThrownBy(() -> service.authenticate("tok", AcceptedLegalVersions.NONE))
                .isInstanceOfSatisfying(AcceptanceRequiredException.class, e -> {
                    assertThat(e.pendingTermsVersion()).isEqualTo(V);
                    assertThat(e.pendingDataPolicyVersion()).isEqualTo(V);
                });
        verify(credentials, never()).markUsed(anyString(), any());

        when(accounts.recordLegalAcceptancesIfUnchanged(eq(ACCOUNT), any(), any(), any())).thenReturn(true);
        assertThat(service.authenticate("tok", new AcceptedLegalVersions(V, V)).accountId()).isEqualTo("acc-1");
        verify(accounts).recordLegalAcceptancesIfUnchanged(eq(ACCOUNT), any(), any(), any());
        verify(credentials).markUsed(eq("cred-1"), any(Instant.class));
    }

    @Test
    void revocarUnaQueNoEsDeLaCuentaOYaRevocada_esNoEncontrada() {
        when(credentials.revokeIfActive(eq("cred-1"), eq(ACCOUNT), any())).thenReturn(false);

        assertThatThrownBy(() -> service.revoke(ACCOUNT, "cred-1")).isExactlyInstanceOf(DeviceCredentialNotFoundException.class);
    }
}
