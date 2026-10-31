package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.LegalAcceptance;
import identity.domain.model.LegalVersions;
import identity.domain.model.PasswordHash;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static final String V1 = "v1-2026-10";
    private static final LegalVersions CURRENT = new LegalVersions(V1, V1, V1);
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final LegalAcceptance ACCEPTED_V1 = new LegalAcceptance(V1, Instant.parse("2026-10-01T09:00:00Z"));

    private AuthenticateAccountPortImpl port(PasswordHasherPort passwordHasher) {
        return new AuthenticateAccountPortImpl(accounts, passwordHasher, () -> CURRENT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Al día con las versiones vigentes: el login no escribe nada (ADR-050 D3). */
    private Account account(AccountStatus status) {
        return withAcceptances(status, ACCEPTED_V1, ACCEPTED_V1);
    }

    /** Con el correo verificado (ADR-050 Enmienda 2, E2-3): así estos tests prueban lo que probaban antes. */
    private Account withAcceptances(AccountStatus status, LegalAcceptance terms, LegalAcceptance dataPolicy) {
        return Account.reconstitute(new AccountId("acc-1"), new Email(EMAIL), storedHash, status, null, null, null, null,
                null, terms, dataPolicy, VERIFIED_AT);
    }

    private static final Instant VERIFIED_AT = Instant.parse("2026-10-01T09:05:00Z");

    private Account unverified(AccountStatus status, LegalAcceptance terms, LegalAcceptance dataPolicy) {
        return Account.reconstitute(new AccountId("acc-1"), new Email(EMAIL), storedHash, status, null, null, null, null,
                null, terms, dataPolicy, null);
    }

    // --- ADR-050 Enmienda 2, E2-3: contraseña → estado → correo verificado → aceptaciones ---

    @Test
    void e2_anUnverifiedAccountWithTheRightPassword_isEmailNotVerified() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(unverified(AccountStatus.ACTIVE, ACCEPTED_V1, ACCEPTED_V1)));

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(com.traceability.contracts.authentication.EmailNotVerifiedException.class);
    }

    @Test
    void e2_anUnverifiedAccountWithAWrongPasswordOrInactive_isTheUsualFailure() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(unverified(AccountStatus.ACTIVE, ACCEPTED_V1, ACCEPTED_V1)));
        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, "wrong", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);

        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(unverified(AccountStatus.INACTIVE, ACCEPTED_V1, ACCEPTED_V1)));
        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void e2_theVerificationGoesBeforeTheAcceptances_andWritesNothing() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(unverified(AccountStatus.ACTIVE, null, null)));

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, new AcceptedLegalVersions(V1, V1)))
                .isExactlyInstanceOf(com.traceability.contracts.authentication.EmailNotVerifiedException.class);
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.never())
                .recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
    }

    @Test
    void correctCredentials_returnTheAccountId() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        assertThat(port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE).accountId())
                .isEqualTo("acc-1");
    }

    // ADR-050 Enmienda 1, E1-1 (plan P-R3-3, A-1): el credentialsNotBefore del mismo documento que validó la contraseña
    @Test
    void correctCredentials_returnTheCredentialsNotBeforeOfThatSameAccount() {
        java.time.Instant cnb = java.time.Instant.parse("2026-10-25T10:00:03Z");
        Account withCnb = Account.reconstitute(new AccountId("acc-1"), new Email(EMAIL), storedHash, AccountStatus.ACTIVE,
                null, null, cnb, null, null, ACCEPTED_V1, ACCEPTED_V1, VERIFIED_AT);
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withCnb));

        assertThat(port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isEqualTo(new com.traceability.contracts.authentication.AuthenticatedAccount("acc-1", cnb));
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(1)).findByEmail(new Email(EMAIL));
    }

    @Test
    void anAccountWithoutCredentialsNotBefore_returnsNull() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        assertThat(port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE).credentialsNotBefore())
                .isNull();
    }

    @Test
    void wrongPassword_fails() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, "wrong", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void unknownEmail_failsWithTheSameException_afterComparingAgainstTheDummyHash() {
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
        AuthenticateAccountPortImpl port = port(hasher);

        assertThatThrownBy(() -> port.authenticate("nobody@example.org", PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), any(PasswordHash.class));
    }

    @Test
    void malformedEmail_isTreatedAsUnknown() {
        AuthenticateAccountPortImpl port = port(hasher);

        assertThatThrownBy(() -> port.authenticate("not-an-email", PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), any(PasswordHash.class));
    }

    @Test
    void inactiveAccount_failsWithTheSameException_evenWithTheRightPassword_andStillComparesThePassword() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.INACTIVE)));
        AuthenticateAccountPortImpl port = port(hasher);

        assertThatThrownBy(() -> port.authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(hasher, times(1)).matches(eq(PASSWORD), eq(storedHash));
    }

    @Test
    void theExceptionCarriesNoReason() {
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
        AuthenticateAccountPortImpl port = port(hasher);

        assertThatThrownBy(() -> port.authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
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
        AuthenticateAccountPortImpl port = port(new BCryptPasswordHasherAdapter());

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
            port.authenticate(email, password, AcceptedLegalVersions.NONE);
        } catch (AuthenticationFailedException expected) {
            // esperado
        }
        return System.nanoTime() - start;
    }

    // ── ADR-050 D3 (Enmienda 1, parte 3, E3-2 y E3-3) ──

    private static final AcceptedLegalVersions BOTH_V1 = new AcceptedLegalVersions(V1, V1);

    @Test
    void d3_pendingVersions_withoutAcceptingThem_isAcceptanceRequired_withOnlyThePending_andWritesNothing() {
        when(accounts.findByEmail(new Email(EMAIL)))
                .thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, ACCEPTED_V1, null)));

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AcceptanceRequiredException.class)
                .satisfies(e -> {
                    assertThat(((AcceptanceRequiredException) e).pendingTermsVersion()).isNull();
                    assertThat(((AcceptanceRequiredException) e).pendingDataPolicyVersion()).isEqualTo(V1);
                });
        verify(accounts, never()).recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
    }

    @Test
    void d3_aWrongPassword_withPendingVersions_isTheSameAuthenticationFailure_neverAcceptanceRequired() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, null, null)));
        when(accounts.findByEmail(new Email("nobody@example.org"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, "wrong", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> port(hasher).authenticate("nobody@example.org", PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withAcceptances(AccountStatus.INACTIVE, null, null)));
        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(accounts, never()).recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
    }

    @Test
    void d3_acceptingExactlyThePendingVersions_storesOnlyThoseWithTheDate_andAuthenticates() {
        when(accounts.findByEmail(new Email(EMAIL)))
                .thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, ACCEPTED_V1, null)));
        when(accounts.recordLegalAcceptancesIfUnchanged(any(), any(), any(), any())).thenReturn(true);

        assertThat(port(hasher).authenticate(EMAIL, PASSWORD, BOTH_V1).accountId()).isEqualTo("acc-1");

        // solo la pendiente (la de datos); la de los términos ya estaba al día y no se toca, ni su fecha
        verify(accounts).recordLegalAcceptancesIfUnchanged(eq(new AccountId("acc-1")), eq(storedHash), isNull(),
                eq(new LegalAcceptance(V1, NOW)));
    }

    @Test
    void d3_otherVersionsThanTheCurrent_areAcceptanceRequired_andNothingIsStored() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, null, null)));

        for (AcceptedLegalVersions other : java.util.List.of(new AcceptedLegalVersions("v0-2026-01", V1),
                new AcceptedLegalVersions(V1, "v0-2026-01"), new AcceptedLegalVersions(V1, null))) {
            assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, other))
                    .as(other.toString()).isExactlyInstanceOf(AcceptanceRequiredException.class);
        }
        verify(accounts, never()).recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
    }

    @Test
    void d3_anUpToDateAccount_writesNothing() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        port(hasher).authenticate(EMAIL, PASSWORD, BOTH_V1);
        port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE);

        verify(accounts, never()).recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
        verify(accounts, never()).save(any());
    }

    @Test
    void d3_anOldAcceptedVersion_isPendingAgain() {
        LegalAcceptance old = new LegalAcceptance("v0-2026-01", Instant.parse("2026-01-10T09:00:00Z"));
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, old, old)));

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AcceptanceRequiredException.class)
                .satisfies(e -> assertThat(((AcceptanceRequiredException) e).pendingTermsVersion()).isEqualTo(V1));
    }

    @Test
    void d3_ifThePartialWriteFindsAnotherHash_theLoginIsTheSameAuthenticationFailure() {
        when(accounts.findByEmail(new Email(EMAIL))).thenReturn(Optional.of(withAcceptances(AccountStatus.ACTIVE, null, null)));
        when(accounts.recordLegalAcceptancesIfUnchanged(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> port(hasher).authenticate(EMAIL, PASSWORD, BOTH_V1))
                .isExactlyInstanceOf(AuthenticationFailedException.class);
        verify(accounts, never()).save(any());
    }
}
