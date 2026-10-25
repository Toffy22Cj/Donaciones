package identity.application.service;

import com.mongodb.MongoException;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.application.port.out.PasswordResetMailPort;
import identity.application.port.out.PasswordResetMailPort.PasswordResetMail;
import identity.application.port.out.PasswordResetTokenRepositoryPort;
import identity.domain.exception.PasswordResetTokenInvalidException;
import identity.domain.exception.PasswordTooShortException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.InvitationToken;
import identity.domain.model.PasswordResetToken;
import identity.domain.model.PasswordResetTokenStatus;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.PasswordResetTokenDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoPasswordResetTokenRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * ADR-050 D8 y Q5 (a) (plan R-4 §6, {@code PasswordResetServicesIntegrationTest}), en Mongo real: la petición
 * ({@link RequestPasswordResetService}) y la confirmación ({@link ChangeCredentialsService#resetCredentials}).
 */
@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        MongoPasswordResetTokenRepositoryAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        ChangeCredentialsService.class,
        DeactivateAccountService.class,
        RequestPasswordResetService.class,
        identity.application.authorization.AuthorizationAuditActorMapper.class,
        PasswordResetServicesIntegrationTest.TestBeans.class
})
class PasswordResetServicesIntegrationTest extends BaseMongoIntegrationTest {

    private static final String PASSWORD = "OldPassword123!";
    private static final String NEW_PASSWORD = "NewPassword456!";

    /** Reloj que el test mueve a mano. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-25T10:00:00.500Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Captura lo que la petición entrega para enviar. */
    static final class CapturingMail implements PasswordResetMailPort {
        final List<PasswordResetMail> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(PasswordResetMail mail) {
            dispatched.add(mail);
        }
    }

    @TestConfiguration
    static class TestBeans {
        static final MutableClock CLOCK = new MutableClock();
        static final CapturingMail MAIL = new CapturingMail();

        @Bean Clock clock() { return CLOCK; }
        @Bean PasswordResetMailPort passwordResetMail() { return MAIL; }
    }

    @Autowired private CreateAccountService accounts;
    @Autowired private DeactivateAccountService deactivations;
    @Autowired private RequestPasswordResetService requests;
    @Autowired private ChangeCredentialsService credentials;
    @Autowired private PasswordHasherPort hasher;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MongoMappingContext mappingContext;
    @MockitoSpyBean private PasswordResetTokenRepositoryPort resetTokens;
    @MockitoSpyBean private AuditLogPort auditLog;
    @MockitoSpyBean private identity.application.port.out.AccountRepositoryPort accountRepository;

    @BeforeEach
    void setUp() {
        // el índice único parcial de una PENDING por cuenta, como MongoIndexInitializer en app
        var resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        resolver.resolveIndexFor(PasswordResetTokenDocument.class)
                .forEach(index -> mongoTemplate.indexOps(PasswordResetTokenDocument.class).ensureIndex(index));
        TestBeans.MAIL.dispatched.clear();
        TestBeans.CLOCK.now = Instant.parse("2026-10-25T10:00:00.500Z");
        reset(resetTokens, auditLog, accountRepository);
    }

    private static String newEmail() {
        return "reset-" + UUID.randomUUID() + "@example.org";
    }

    private Account account(String email) {
        return accounts.createAccount(new Email(email), PASSWORD);
    }

    private List<PasswordResetTokenDocument> tokensOf(AccountId accountId) {
        return mongoTemplate.find(Query.query(Criteria.where("accountId").is(accountId.value())),
                PasswordResetTokenDocument.class);
    }

    private String requestAndTakeSecret(String email) {
        int before = TestBeans.MAIL.dispatched.size();
        requests.request(email);
        assertThat(TestBeans.MAIL.dispatched).hasSize(before + 1);
        return TestBeans.MAIL.dispatched.get(before).token();
    }

    // ── Petición ──

    @Test
    void request_withAnActiveAccount_storesOnlyTheHash_andDispatchesOneMailWithTheSecret() {
        String email = newEmail();
        Account account = account(email);

        requests.request(email);

        List<PasswordResetTokenDocument> stored = tokensOf(account.getAccountId());
        assertThat(stored).hasSize(1);
        PasswordResetMail mail = TestBeans.MAIL.dispatched.get(0);
        assertThat(mail.to()).isEqualTo(email);
        assertThat(mail.tokenId()).isEqualTo(stored.get(0).getId());
        assertThat(stored.get(0).getTokenHash()).isEqualTo(InvitationToken.hashOf(mail.token()));
        assertThat(stored.get(0).getTokenHash()).isNotEqualTo(mail.token());
        assertThat(stored.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(mail.expiresAt()).isEqualTo(TestBeans.CLOCK.now.plusSeconds(1800));
        assertThat(mail.toString()).doesNotContain(mail.token()).doesNotContain(email);
    }

    @Test
    void request_withoutAccount_malformedEmail_orInactiveAccount_writesNothingAndDispatchesNothing() {
        String inactive = newEmail();
        Account account = account(inactive);
        deactivations.deactivateAccount(new AuditActor.AccountAuditActor(account.getAccountId()), account.getAccountId());
        reset(resetTokens);

        requests.request(newEmail());
        requests.request("not-an-email");
        requests.request(inactive);

        assertThat(TestBeans.MAIL.dispatched).isEmpty();
        verify(resetTokens, never()).insert(any());
        verify(resetTokens, never()).supersedePendingFor(any());
        assertThat(tokensOf(account.getAccountId())).isEmpty();
    }

    @Test
    void request_twiceWithinFiveMinutes_isTheCooldown_noNewTokenAndNoMail() {
        String email = newEmail();
        Account account = account(email);
        requests.request(email);

        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(299);
        requests.request(email);

        assertThat(TestBeans.MAIL.dispatched).hasSize(1);
        assertThat(tokensOf(account.getAccountId())).hasSize(1);
    }

    @Test
    void request_afterFiveMinutes_supersedesThePreviousToken_andSendsANewOne() {
        String email = newEmail();
        Account account = account(email);
        String first = requestAndTakeSecret(email);

        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(300);
        String second = requestAndTakeSecret(email);

        assertThat(second).isNotEqualTo(first);
        assertThat(tokensOf(account.getAccountId())).extracting(PasswordResetTokenDocument::getStatus)
                .containsExactlyInAnyOrder("SUPERSEDED", "PENDING");
        assertThatThrownBy(() -> credentials.resetCredentials(first, NEW_PASSWORD))
                .isInstanceOf(PasswordResetTokenInvalidException.class);
    }

    @Test
    void twoSimultaneousRequests_leaveASinglePending_andASingleMail() throws Exception {
        String email = newEmail();
        Account account = account(email);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> { barrier.await(); requests.request(email); return null; }),
                    pool.submit(() -> { barrier.await(); requests.request(email); return null; }));
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tokensOf(account.getAccountId())).filteredOn(d -> d.getStatus().equals("PENDING")).hasSize(1);
        assertThat(TestBeans.MAIL.dispatched).hasSize(1);
    }

    @Test
    void aTransactionThatFails_dispatchesNothing() {
        String email = newEmail();
        account(email);
        doAnswer(inv -> { throw new IllegalStateException("insert failed"); }).when(resetTokens).insert(any());

        assertThatThrownBy(() -> requests.request(email)).isInstanceOf(IllegalStateException.class);

        assertThat(TestBeans.MAIL.dispatched).isEmpty();
    }

    @Test
    void afterATransientRetry_theMailCarriesTheSecretOfTheAttemptThatCommitted() {
        String email = newEmail();
        Account account = account(email);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            if (attempts.getAndIncrement() == 0) {
                MongoException transientError = new MongoException("simulated write conflict");
                transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                throw transientError;
            }
            return inv.callRealMethod();
        }).when(resetTokens).insert(any());

        requests.request(email);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(TestBeans.MAIL.dispatched).hasSize(1);
        List<PasswordResetTokenDocument> stored = tokensOf(account.getAccountId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash()).isEqualTo(InvitationToken.hashOf(TestBeans.MAIL.dispatched.get(0).token()));
    }

    // ── Confirmación ──

    @Test
    void confirm_changesThePassword_setsCredentialsNotBefore_usesTheToken_andAuditsViaPasswordReset() {
        String email = newEmail();
        Account account = account(email);
        String secret = requestAndTakeSecret(email);
        reset(auditLog);

        credentials.resetCredentials(secret, NEW_PASSWORD);

        Account changed = accountRepository.findById(account.getAccountId());
        assertThat(hasher.matches(NEW_PASSWORD, changed.getPasswordHash())).isTrue();
        assertThat(changed.getCredentialsNotBefore())
                .isEqualTo(Instant.parse("2026-10-25T10:00:00Z").plusSeconds(1));
        assertThat(tokensOf(account.getAccountId())).singleElement()
                .satisfies(d -> {
                    assertThat(d.getStatus()).isEqualTo("USED");
                    assertThat(d.getUsedAt()).isEqualTo(TestBeans.CLOCK.now);
                });
        ArgumentCaptor<AuditLogEntry> entry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLog).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.CREDENTIALS_CHANGED);
        assertThat(entry.getValue().actor()).isEqualTo(new AuditActor.AccountAuditActor(account.getAccountId()));
        assertThat(entry.getValue().changeSummary()).isEqualTo(java.util.Map.of("via", "PASSWORD_RESET"));
        assertThat(entry.getValue().toString()).doesNotContain(NEW_PASSWORD).doesNotContain(secret);
    }

    /** ADR-050 test 14: desconocido, caducado, usado, reemplazado y de cuenta inactiva dan la misma excepción. */
    @Test
    void confirm_withAnUnknownExpiredUsedSupersededOrInactiveToken_isTheSameException_andNothingChanges() {
        // desconocido
        assertThatThrownBy(() -> credentials.resetCredentials("unknown-token", NEW_PASSWORD))
                .isExactlyInstanceOf(PasswordResetTokenInvalidException.class);

        // caducado
        String expiredEmail = newEmail();
        Account expiredAccount = account(expiredEmail);
        String expired = requestAndTakeSecret(expiredEmail);
        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(1800);
        assertThatThrownBy(() -> credentials.resetCredentials(expired, NEW_PASSWORD))
                .isExactlyInstanceOf(PasswordResetTokenInvalidException.class);
        assertUnchanged(expiredAccount.getAccountId(), "PENDING");

        // usado
        String usedEmail = newEmail();
        Account usedAccount = account(usedEmail);
        String used = requestAndTakeSecret(usedEmail);
        credentials.resetCredentials(used, NEW_PASSWORD);
        assertThatThrownBy(() -> credentials.resetCredentials(used, "OtherPassword789!"))
                .isExactlyInstanceOf(PasswordResetTokenInvalidException.class);
        assertThat(hasher.matches(NEW_PASSWORD, accountRepository.findById(usedAccount.getAccountId()).getPasswordHash()))
                .isTrue();

        // reemplazado
        String supersededEmail = newEmail();
        account(supersededEmail);
        String superseded = requestAndTakeSecret(supersededEmail);
        TestBeans.CLOCK.now = TestBeans.CLOCK.now.plusSeconds(300);
        requestAndTakeSecret(supersededEmail);
        assertThatThrownBy(() -> credentials.resetCredentials(superseded, NEW_PASSWORD))
                .isExactlyInstanceOf(PasswordResetTokenInvalidException.class);

        // cuenta inactiva
        String inactiveEmail = newEmail();
        Account inactive = account(inactiveEmail);
        String forInactive = requestAndTakeSecret(inactiveEmail);
        deactivations.deactivateAccount(new AuditActor.AccountAuditActor(inactive.getAccountId()), inactive.getAccountId());
        assertThatThrownBy(() -> credentials.resetCredentials(forInactive, NEW_PASSWORD))
                .isExactlyInstanceOf(PasswordResetTokenInvalidException.class);
        assertThat(hasher.matches(PASSWORD, accountRepository.findById(inactive.getAccountId()).getPasswordHash()))
                .isTrue();
        assertThat(tokensOf(inactive.getAccountId())).singleElement()
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo("PENDING"));
    }

    private void assertUnchanged(AccountId accountId, String tokenStatus) {
        Account account = accountRepository.findById(accountId);
        assertThat(hasher.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.getCredentialsNotBefore()).isNull();
        assertThat(tokensOf(accountId)).singleElement().satisfies(d -> assertThat(d.getStatus()).isEqualTo(tokenStatus));
    }

    @Test
    void confirm_withAShortPassword_isPasswordTooShort_andTheTokenIsNotConsumed() {
        String email = newEmail();
        Account account = account(email);
        String secret = requestAndTakeSecret(email);

        assertThatThrownBy(() -> credentials.resetCredentials(secret, "short"))
                .isInstanceOf(PasswordTooShortException.class);

        assertUnchanged(account.getAccountId(), "PENDING");
        verify(resetTokens, never()).findByTokenHash(any());
        credentials.resetCredentials(secret, NEW_PASSWORD);
    }

    /** ADR-050 test 15: dos confirmaciones simultáneas del mismo token, una gana. */
    @Test
    void twoSimultaneousConfirmations_oneWins_andTheOtherIsInvalid() throws Exception {
        String email = newEmail();
        Account account = account(email);
        String secret = requestAndTakeSecret(email);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger invalid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> confirmCounting(barrier, secret, "WinnerPassword111!", ok, invalid)),
                    pool.submit(() -> confirmCounting(barrier, secret, "WinnerPassword222!", ok, invalid)));
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).isEqualTo(1);
        assertThat(invalid.get()).isEqualTo(1);
        verify(auditLog, atLeastOnce()).record(any());
        assertThat(tokensOf(account.getAccountId())).singleElement()
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo("USED"));
    }

    private Void confirmCounting(CyclicBarrier barrier, String secret, String password, AtomicInteger ok,
                                 AtomicInteger invalid) throws Exception {
        barrier.await();
        try {
            credentials.resetCredentials(secret, password);
            ok.incrementAndGet();
        } catch (PasswordResetTokenInvalidException | identity.domain.exception.IdentityConcurrentModificationException e) {
            invalid.incrementAndGet();
        }
        return null;
    }

    @Test
    void theStoredTokenState_isPendingAfterTheRequest() {
        String email = newEmail();
        Account account = account(email);
        requests.request(email);

        PasswordResetToken token = resetTokens.findPendingByAccount(account.getAccountId()).orElseThrow();
        assertThat(token.getStatus()).isEqualTo(PasswordResetTokenStatus.PENDING);
    }

    @Test
    void markUsedIfPending_onlyMovesAPendingToken_once() {
        // contrato del adaptador (plan R-4 §4): PENDING → USED condicional; un token ya USED no se vuelve a marcar
        String email = newEmail();
        Account account = account(email);
        requests.request(email);
        PasswordResetToken token = resetTokens.findPendingByAccount(account.getAccountId()).orElseThrow();
        Instant first = Instant.parse("2026-10-25T10:00:00Z");

        assertThat(resetTokens.markUsedIfPending(token.getId(), first)).isTrue();
        assertThat(resetTokens.markUsedIfPending(token.getId(), first.plusSeconds(60))).isFalse();

        PasswordResetTokenDocument stored = tokensOf(account.getAccountId()).get(0);
        assertThat(stored.getStatus()).isEqualTo("USED");
        assertThat(stored.getUsedAt()).isEqualTo(first);
    }
}
