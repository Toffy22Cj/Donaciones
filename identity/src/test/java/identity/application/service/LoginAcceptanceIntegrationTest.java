package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.Account;
import identity.domain.model.Email;
import identity.domain.model.LegalVersions;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * [Plan: nombre] ADR-050 D3 (Enmienda 1, parte 3, E3-2 y E3-3), en Mongo real. Numeración de la definición de hecho
 * de la parte 3: 1, 2, 4, 5 y 6. El 3 (el mismo 401 byte a byte) va por HTTP, en {@code app}.
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        EmailVerificationTestBeans.class, // ADR-050 Enmienda 2: el servicio de verificación y los dos puertos de correo
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        AuthenticateAccountPortImpl.class,
        LoginAcceptanceIntegrationTest.TestBeans.class
})
class LoginAcceptanceIntegrationTest extends BaseMongoIntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    static final String CURRENT = "v1-2026-10";
    static final String PASSWORD = "Password123!long";
    static final AcceptedLegalVersions BOTH_CURRENT = new AcceptedLegalVersions(CURRENT, CURRENT);

    @TestConfiguration
    static class TestBeans {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean LegalVersions legalVersions() { return new LegalVersions(CURRENT, CURRENT, CURRENT); }
    }

    @Autowired private CreateAccountService accounts;
    @Autowired private AuthenticateAccountPort login;
    @Autowired private EmailVerificationService verifications;
    @Autowired private PasswordHasherPort hasher;
    @Autowired private MongoTemplate mongoTemplate;
    @MockitoSpyBean private AccountRepositoryPort accountRepository;

    @BeforeEach
    void setUp() {
        reset(accountRepository);
    }

    private static String newEmail() {
        return "d3-" + UUID.randomUUID() + "@example.org";
    }

    /** Una cuenta sin aceptaciones, como las anteriores a R-5 y las de la semilla. */
    private Account withoutAcceptances(String email) {
        return accounts.createAccount(new Email(email), PASSWORD);
    }

    private Document doc(Account account) {
        return mongoTemplate.findById(account.getAccountId().value(), Document.class, "accounts");
    }

    // ── 1 ──

    @Test
    void test1_pendingVersions_withoutThemInTheBody_isAcceptanceRequired_withBothPending_andNothingStored() {
        String email = newEmail();
        Account account = withoutAcceptances(email);

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(AcceptanceRequiredException.class)
                .satisfies(e -> {
                    assertThat(((AcceptanceRequiredException) e).pendingTermsVersion()).isEqualTo(CURRENT);
                    assertThat(((AcceptanceRequiredException) e).pendingDataPolicyVersion()).isEqualTo(CURRENT);
                });
        assertThat(doc(account).containsKey("termsVersion")).isFalse();
    }

    // ── 2 ──

    @Test
    void test2_repeatingTheLoginWithTheCurrentVersions_storesThemWithTheDate_andTheNextLoginNeedsNothing() {
        String email = newEmail();
        Account account = withoutAcceptances(email);

        assertThat(login.authenticate(email, PASSWORD, BOTH_CURRENT).accountId()).isEqualTo(account.getAccountId().value());

        Document stored = doc(account);
        assertThat(stored.getString("termsVersion")).isEqualTo(CURRENT);
        assertThat(stored.getString("dataPolicyVersion")).isEqualTo(CURRENT);
        assertThat(stored.getDate("termsAcceptedAt")).isEqualTo(Date.from(NOW));
        assertThat(stored.getDate("dataPolicyAcceptedAt")).isEqualTo(Date.from(NOW));
        // la escritura es parcial: el resto del documento no cambia
        assertThat(stored.getString("passwordHash")).isEqualTo(account.getPasswordHash().value());
        assertThat(stored.getString("status")).isEqualTo("ACTIVE");

        assertThat(login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE).accountId())
                .isEqualTo(account.getAccountId().value());
    }

    // ── 4 ──

    @Test
    void test4_otherVersionsThanTheCurrent_areAcceptanceRequired_andNothingIsStored() {
        String email = newEmail();
        Account account = withoutAcceptances(email);

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, new AcceptedLegalVersions("v0-2026-01", CURRENT)))
                .isExactlyInstanceOf(AcceptanceRequiredException.class);
        Document stored = doc(account);
        assertThat(stored.containsKey("termsVersion")).isFalse();
        assertThat(stored.containsKey("dataPolicyVersion")).isFalse();
    }

    // ── 5 ──

    @Test
    void test5_anUpToDateAccount_writesNothing() {
        String email = newEmail();
        Account account = accounts.createAccount(new Email(email), PASSWORD, CURRENT, CURRENT);
        // ADR-050 Enmienda 2, E2-3: el registro público nace sin verificar; se verifica antes de iniciar sesión
        verifications.confirm(EmailVerificationTestBeans.VERIFICATION_MAILS.stream()
                .filter(m -> m.to().equals(email)).findFirst().orElseThrow().token());
        reset(accountRepository);

        login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE);
        login.authenticate(email, PASSWORD, BOTH_CURRENT);

        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
                .recordLegalAcceptancesIfUnchanged(any(), any(), any(), any());
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never()).save(any());
        assertThat(doc(account).getString("termsVersion")).isEqualTo(CURRENT);
    }

    // ── 6 ──

    @Test
    void test6_aPasswordChangeBetweenTheReadAndTheWrite_isA401_keepsTheNewPassword_andStoresNoAcceptance() {
        String email = newEmail();
        Account account = withoutAcceptances(email);
        String newHash = hasher.hash("NewPassword456!long").value();
        // el cambio de contraseña llega justo después de la lectura que valida la contraseña anterior
        doAnswer(inv -> {
            Object read = inv.callRealMethod();
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(account.getAccountId().value())),
                    Update.update("passwordHash", newHash), "accounts");
            return read;
        }).when(accountRepository).findByEmail(any());

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, BOTH_CURRENT))
                .isExactlyInstanceOf(AuthenticationFailedException.class);

        Document stored = doc(account);
        assertThat(stored.getString("passwordHash")).isEqualTo(newHash);
        assertThat(stored.containsKey("termsVersion")).isFalse();
        assertThat(stored.containsKey("dataPolicyVersion")).isFalse();
    }

    @Test
    void test6_aDeactivationBetweenTheReadAndTheWrite_isA401_andStoresNoAcceptance() {
        String email = newEmail();
        Account account = withoutAcceptances(email);
        doAnswer(inv -> {
            Object read = inv.callRealMethod();
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(account.getAccountId().value())),
                    Update.update("status", "INACTIVE"), "accounts");
            return read;
        }).when(accountRepository).findByEmail(any());

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, BOTH_CURRENT))
                .isExactlyInstanceOf(AuthenticationFailedException.class);

        Document stored = doc(account);
        assertThat(stored.getString("status")).isEqualTo("INACTIVE");
        assertThat(stored.containsKey("termsVersion")).isFalse();
    }
}
