package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.port.out.EmailVerificationMailPort.EmailVerificationMail;
import identity.application.port.out.PasswordResetMailPort;
import identity.application.port.out.SecurityNoticeMailPort.SecurityNotice;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.exception.EmailVerificationTokenInvalidException;
import identity.domain.exception.PasswordTooShortException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.InvitationToken;
import identity.domain.model.LegalVersions;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import identity.infrastructure.persistence.mongo.documents.EmailVerificationTokenDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoPasswordResetTokenRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoPlatformAuthorityStateAdapter;
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
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ContextConfiguration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Plan] ADR-050 Enmienda 2 en los servicios de {@code identity}, con Mongo real (plan §2): verificación (tests 1, 3 a
 * 9 de la Definición de hecho) y avisos (11 y 13). Los tests 2, 10 y 12, que miran la respuesta HTTP y los logs, van
 * por HTTP en {@code app} ({@code EmailVerificationHttpIntegrationTest}).
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        MongoPasswordResetTokenRepositoryAdapter.class,
        MongoPlatformAuthorityStateAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        ChangeCredentialsService.class,
        DeactivateAccountService.class,
        RequestPasswordResetService.class,
        BootstrapPlatformAuthorityService.class,
        AuthenticateAccountPortImpl.class,
        StubCampaignResponsibilityPort.class,
        identity.application.authorization.AuthorizationAuditActorMapper.class,
        EmailVerificationTestBeans.class,
        EmailVerificationServicesIntegrationTest.TestBeans.class
})
class EmailVerificationServicesIntegrationTest extends BaseMongoIntegrationTest {

    static final String CURRENT = "v1-2026-10";
    static final String PASSWORD = "Password123!long";
    static final String NEW_PASSWORD = "NewPassword456!long";
    static final AcceptedLegalVersions BOTH_CURRENT = new AcceptedLegalVersions(CURRENT, CURRENT);

    /** Reloj que el test mueve a mano. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-29T15:00:00.250Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class TestBeans {
        static final MutableClock CLOCK = new MutableClock();
        static final List<PasswordResetMailPort.PasswordResetMail> RESET_MAILS = new CopyOnWriteArrayList<>();

        @Bean Clock clock() { return CLOCK; }
        @Bean LegalVersions legalVersions() { return new LegalVersions(CURRENT, CURRENT, CURRENT); }
        @Bean PasswordResetMailPort passwordResetMail() { return RESET_MAILS::add; }
    }

    @Autowired private CreateAccountService accounts;
    @Autowired private EmailVerificationService verifications;
    @Autowired private ChangeCredentialsService credentials;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private RequestPasswordResetService resetRequests;
    @Autowired private BootstrapPlatformAuthorityService bootstrap;
    @Autowired private AuthenticateAccountPort login;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MongoMappingContext mappingContext;

    @BeforeEach
    void setUp() {
        var resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        resolver.resolveIndexFor(EmailVerificationTokenDocument.class)
                .forEach(index -> mongoTemplate.indexOps(EmailVerificationTokenDocument.class).ensureIndex(index));
        EmailVerificationTestBeans.clear();
        TestBeans.RESET_MAILS.clear();
        TestBeans.CLOCK.now = Instant.parse("2026-10-29T15:00:00.250Z");
    }

    private static String newEmail() {
        return "e2-" + UUID.randomUUID() + "@example.org";
    }

    /** Registro público (D1, D2): nace sin verificar y con un correo de verificación entregado. */
    private Account register(String email) {
        return accounts.createAccount(new Email(email), PASSWORD, CURRENT, CURRENT);
    }

    private EmailVerificationMail lastMailTo(String email) {
        List<EmailVerificationMail> to = EmailVerificationTestBeans.VERIFICATION_MAILS.stream()
                .filter(m -> m.to().equals(email)).toList();
        assertThat(to).as("correos de verificación a " + email).isNotEmpty();
        return to.get(to.size() - 1);
    }

    private Document accountDoc(AccountId id) {
        return mongoTemplate.findById(id.value(), Document.class, "accounts");
    }

    private List<EmailVerificationTokenDocument> tokensOf(AccountId id) {
        return mongoTemplate.find(Query.query(Criteria.where("accountId").is(id.value())), EmailVerificationTokenDocument.class);
    }

    private long verifiedAudits(AccountId id) {
        return mongoTemplate.count(Query.query(Criteria.where("targetAccountId").is(id.value())
                .and("action").is(AuditAction.EMAIL_VERIFIED.name())), AuditLogEntryDocument.class);
    }

    private static AuthorizationPrincipal principal(AccountId id) {
        return new AuthorizationPrincipal(id.value(), null, Set.of(), null);
    }

    private List<SecurityNotice> noticesTo(String email) {
        return EmailVerificationTestBeans.NOTICES.stream().filter(n -> n.to().equals(email)).toList();
    }

    // ── 1 ──

    @Test
    void test1_registering_leavesTheAccountUnverified_andDeliversOneMailWithTheTokenWhoseHashIsStored() {
        String email = newEmail();
        Account account = register(email);

        assertThat(accountDoc(account.getAccountId()).get("emailVerifiedAt")).isNull();
        EmailVerificationMail mail = lastMailTo(email);
        List<EmailVerificationTokenDocument> stored = tokensOf(account.getAccountId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(stored.get(0).getId()).isEqualTo(mail.tokenId());
        assertThat(stored.get(0).getTokenHash()).isEqualTo(InvitationToken.hashOf(mail.token())).isNotEqualTo(mail.token());
        assertThat(mail.expiresAt()).isEqualTo(TestBeans.CLOCK.now.plus(Duration.ofHours(24)));
        assertThat(mail.toString()).doesNotContain(mail.token()).doesNotContain(email);
    }

    // ── 2 (en el servicio) y 9 ──

    @Test
    void test2_9_unverified_rightPassword_isEmailNotVerified_beforeTheAcceptances_wrongPassword_isTheUsualFailure() {
        String email = newEmail();
        register(email);

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(EmailNotVerifiedException.class);
        assertThatThrownBy(() -> login.authenticate(email, "Wrong-Password-123", AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(com.traceability.contracts.authentication.AuthenticationFailedException.class);
    }

    @Test
    void test9_anUnverifiedAccountWithoutCurrentAcceptances_isEmailNotVerified_notAcceptanceRequired() {
        String email = newEmail();
        Account account = register(email);
        mongoTemplate.getCollection("accounts").updateOne(new Document("_id", account.getAccountId().value()),
                new Document("$unset", new Document("termsVersion", "").append("dataPolicyVersion", "")));

        assertThatThrownBy(() -> login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE))
                .isExactlyInstanceOf(EmailNotVerifiedException.class);
        assertThat(accountDoc(account.getAccountId()).containsKey("termsVersion")).isFalse();
    }

    // ── 3 ──

    @Test
    void test3_confirmingAValidToken_setsEmailVerifiedAt_audits_andTheNextLoginWorks() {
        String email = newEmail();
        Account account = register(email);

        verifications.confirm(lastMailTo(email).token());

        assertThat(accountDoc(account.getAccountId()).getDate("emailVerifiedAt")).isNotNull();
        assertThat(tokensOf(account.getAccountId())).extracting(EmailVerificationTokenDocument::getStatus).containsExactly("USED");
        assertThat(verifiedAudits(account.getAccountId())).isEqualTo(1);
        assertThat(login.authenticate(email, PASSWORD, AcceptedLegalVersions.NONE).accountId())
                .isEqualTo(account.getAccountId().value());
        // otra escritura completa de la cuenta (save) no borra la verificación
        credentials.changeOwnCredentials(principal(account.getAccountId()), PASSWORD, NEW_PASSWORD);
        assertThat(login.authenticate(email, NEW_PASSWORD, AcceptedLegalVersions.NONE).accountId())
                .isEqualTo(account.getAccountId().value());
    }

    // ── 4 ──

    @Test
    void test4_unknownExpiredUsedSupersededOrInactive_areTheSameInvalidToken() {
        assertThatThrownBy(() -> verifications.confirm("unknown-" + UUID.randomUUID()))
                .isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);
        assertThatThrownBy(() -> verifications.confirm(""))
                .isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);

        String expiredEmail = newEmail();
        register(expiredEmail);
        String expired = lastMailTo(expiredEmail).token();
        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plus(Duration.ofHours(24)).plusSeconds(1);
        assertThatThrownBy(() -> verifications.confirm(expired)).isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);
        TestBeans.CLOCK.now = Instant.parse("2026-10-29T15:00:00.250Z");

        String usedEmail = newEmail();
        register(usedEmail);
        String used = lastMailTo(usedEmail).token();
        verifications.confirm(used);
        // la cuenta ya está verificada y el token está USED: inválido, no el 204 de "ya verificada con PENDING"
        assertThatThrownBy(() -> verifications.confirm(used)).isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);

        String supersededEmail = newEmail();
        register(supersededEmail);
        String superseded = lastMailTo(supersededEmail).token();
        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(300);
        verifications.request(supersededEmail);
        assertThat(lastMailTo(supersededEmail).token()).isNotEqualTo(superseded);
        assertThatThrownBy(() -> verifications.confirm(superseded)).isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);

        String inactiveEmail = newEmail();
        Account inactive = register(inactiveEmail);
        String ofInactive = lastMailTo(inactiveEmail).token();
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("e2-tests"), inactive.getAccountId());
        assertThatThrownBy(() -> verifications.confirm(ofInactive)).isExactlyInstanceOf(EmailVerificationTokenInvalidException.class);
        assertThat(accountDoc(inactive.getAccountId()).get("emailVerifiedAt")).isNull();
    }

    @Test
    void anAlreadyVerifiedAccountWithAPendingToken_isAcceptedWithoutChangesNorANewAudit() {
        String email = newEmail();
        Account account = register(email);
        String pending = lastMailTo(email).token();
        // se verifica por otro camino (la recuperación) y el token sigue PENDING
        resetRequests.request(email);
        credentials.resetCredentials(TestBeans.RESET_MAILS.get(TestBeans.RESET_MAILS.size() - 1).token(), NEW_PASSWORD);
        Object verifiedAt = accountDoc(account.getAccountId()).get("emailVerifiedAt");
        long audits = verifiedAudits(account.getAccountId());

        verifications.confirm(pending);

        assertThat(accountDoc(account.getAccountId()).get("emailVerifiedAt")).isEqualTo(verifiedAt);
        assertThat(verifiedAudits(account.getAccountId())).isEqualTo(audits);
        assertThat(tokensOf(account.getAccountId())).extracting(EmailVerificationTokenDocument::getStatus).containsExactly("PENDING");
    }

    // ── 5 ──

    @Test
    void test5_twoSimultaneousConfirmations_oneWinsAndTheOtherIsInvalid_withASingleAudit() throws Exception {
        String email = newEmail();
        Account account = register(email);
        String token = lastMailTo(email).token();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        verifications.confirm(token);
                        return true;
                    } catch (EmailVerificationTokenInvalidException e) {
                        return false;
                    }
                }));
            }
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(verifiedAudits(account.getAccountId())).isEqualTo(1);
    }

    // ── 6 ──

    @Test
    void test6_resend_onlyAnUnverifiedActiveAccountGetsAMail_withTheCooldown_andTheOldTokenSuperseded() {
        String unverified = newEmail();
        Account account = register(unverified);
        String verified = newEmail();
        register(verified);
        verifications.confirm(lastMailTo(verified).token());
        String inactive = newEmail();
        Account inactiveAccount = register(inactive);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("e2-tests"), inactiveAccount.getAccountId());
        EmailVerificationTestBeans.clear();

        for (String email : List.of(unverified, verified, newEmail(), inactive, "not-an-email")) {
            verifications.request(email);
        }
        assertThat(EmailVerificationTestBeans.VERIFICATION_MAILS).as("dentro del enfriamiento, nada").isEmpty();

        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(299);
        verifications.request(unverified);
        assertThat(EmailVerificationTestBeans.VERIFICATION_MAILS).isEmpty();

        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(1);
        for (String email : List.of(unverified, verified, newEmail(), inactive)) {
            verifications.request(email);
        }
        assertThat(EmailVerificationTestBeans.VERIFICATION_MAILS).extracting(EmailVerificationMail::to).containsExactly(unverified);
        assertThat(tokensOf(account.getAccountId())).extracting(EmailVerificationTokenDocument::getStatus)
                .containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
    }

    // ── 7 ──

    @Test
    void test7_confirmingAPasswordReset_verifiesTheEmail_withAnAuditViaPasswordReset() {
        String email = newEmail();
        Account account = register(email);
        resetRequests.request(email);

        credentials.resetCredentials(TestBeans.RESET_MAILS.get(TestBeans.RESET_MAILS.size() - 1).token(), NEW_PASSWORD);

        assertThat(accountDoc(account.getAccountId()).getDate("emailVerifiedAt")).isNotNull();
        AuditLogEntryDocument audit = mongoTemplate.findOne(Query.query(Criteria.where("targetAccountId")
                .is(account.getAccountId().value()).and("action").is(AuditAction.EMAIL_VERIFIED.name())), AuditLogEntryDocument.class);
        assertThat(audit.getChangeSummary()).containsEntry("via", "PASSWORD_RESET");
        assertThat(login.authenticate(email, NEW_PASSWORD, AcceptedLegalVersions.NONE).accountId())
                .isEqualTo(account.getAccountId().value());
    }

    // ── 8 ──

    @Test
    void test8_systemAccountsAndTheInitialPlatformAdministrator_areVerified() {
        String seed = newEmail();
        Account system = accounts.createAccount(new Email(seed), PASSWORD);
        assertThat(accountDoc(system.getAccountId()).getDate("emailVerifiedAt")).isNotNull();
        assertThatThrownBy(() -> login.authenticate(seed, PASSWORD, AcceptedLegalVersions.NONE))
                .as("sin aceptaciones: verificada, así que llega a D3").isExactlyInstanceOf(AcceptanceRequiredException.class);
        assertThat(EmailVerificationTestBeans.VERIFICATION_MAILS).isEmpty();

        String admin = newEmail();
        Account target = register(admin);
        mongoTemplate.dropCollection("platform_authority_state");
        mongoTemplate.getCollection("accounts").updateMany(new Document("platformAuthority", new Document("$ne", null)),
                new Document("$unset", new Document("platformAuthority", "")));
        bootstrap.bootstrap(admin);

        assertThat(accountDoc(target.getAccountId()).getDate("emailVerifiedAt")).isNotNull();
        assertThat(login.authenticate(admin, PASSWORD, BOTH_CURRENT).accountId()).isEqualTo(target.getAccountId().value());
    }

    // ── 11 y 13 ──

    @Test
    void test11_changingTheResettingAndDeactivating_eachDeliverOneNotice_withoutTokenNorPassword() {
        String email = newEmail();
        Account account = accounts.createAccount(new Email(email), PASSWORD);

        credentials.changeOwnCredentials(principal(account.getAccountId()), PASSWORD, NEW_PASSWORD);
        resetRequests.request(email);
        credentials.resetCredentials(TestBeans.RESET_MAILS.get(TestBeans.RESET_MAILS.size() - 1).token(), PASSWORD);
        deactivations.deactivateOwnAccount(principal(account.getAccountId()), PASSWORD);

        assertThat(noticesTo(email)).extracting(SecurityNotice::kind).containsExactly(
                SecurityNotice.Kind.PASSWORD_CHANGED, SecurityNotice.Kind.PASSWORD_RESET, SecurityNotice.Kind.ACCOUNT_DEACTIVATED);
        noticesTo(email).forEach(n -> {
            assertThat(n.accountId()).isEqualTo(account.getAccountId().value());
            assertThat(n.toString()).doesNotContain(email).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD);
        });
    }

    @Test
    void test13_aRejectedChange_andTheAdministrativePaths_deliverNoNotice() {
        String email = newEmail();
        Account account = accounts.createAccount(new Email(email), PASSWORD);

        assertThatThrownBy(() -> credentials.changeOwnCredentials(principal(account.getAccountId()), "Wrong-Password-1", NEW_PASSWORD))
                .isInstanceOf(CurrentPasswordIncorrectException.class);
        assertThatThrownBy(() -> credentials.changeOwnCredentials(principal(account.getAccountId()), PASSWORD, "short"))
                .isInstanceOf(PasswordTooShortException.class);
        assertThatThrownBy(() -> deactivations.deactivateOwnAccount(principal(account.getAccountId()), "Wrong-Password-1"))
                .isInstanceOf(CurrentPasswordIncorrectException.class);
        // P-E2V-9: los caminos administrativos no avisan
        credentials.changeCredentials(new AuditActor.SystemAuditActor("e2-tests"), account.getAccountId(), NEW_PASSWORD);
        deactivations.deactivateAccount(new AuditActor.SystemAuditActor("e2-tests"), account.getAccountId());

        assertThat(noticesTo(email)).isEmpty();
    }
}
