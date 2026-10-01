package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.IdentityConcurrentModificationException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.LastPlatformAdministratorException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.PlatformAuthorityAlreadyGrantedException;
import identity.domain.exception.PlatformAuthorityNotHeldException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlatformAuthority;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.documents.PlatformAuthorityStateDocument;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoPlatformAuthorityStateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoPlatformAuthorityStateAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class,
        GrantPlatformAuthorityService.class,
        RevokePlatformAuthorityService.class,
        DeactivateAccountService.class
})
@org.springframework.test.annotation.DirtiesContext
class PlatformAuthorityConcurrencyIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private PlatformAuthorityStatePort platformAuthorityStatePort;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private GrantPlatformAuthorityService grantPlatformAuthorityService;

    @Autowired
    private RevokePlatformAuthorityService revokePlatformAuthorityService;

    @Autowired
    private DeactivateAccountService deactivateAccountService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        reset(accountRepository, platformAuthorityStatePort);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("identity_audit_log");
        mongoTemplate.dropCollection("platform_authority_state");
    }

    private void seedPlatformAuthorityState(long count, long version) {
        PlatformAuthorityStateDocument doc = new PlatformAuthorityStateDocument(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                count,
                version
        );
        mongoTemplate.save(doc, MongoPlatformAuthorityStateAdapter.COLLECTION_NAME);
    }

    private Account seedAccount(AccountId accountId, String email, AccountStatus status, PlatformAuthority authority) {
        Account account = Account.reconstitute(
                accountId,
                new Email(email),
                new PasswordHash("hashed-password"),
                status,
                null,
                authority
        );
        accountRepository.save(account);
        return account;
    }

    private AuthorizationPrincipal createPrincipal(AccountId accountId, PlatformAuthority authority) {
        return new AuthorizationPrincipal(
                accountId.value(),
                null,
                Set.of(),
                authority != null ? com.traceability.contracts.authorization.PlatformAuthority.ADMINISTRATOR : null
        );
    }

    private PlatformAuthorityStateDocument getPlatformAuthorityState() {
        return mongoTemplate.findById(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                PlatformAuthorityStateDocument.class,
                MongoPlatformAuthorityStateAdapter.COLLECTION_NAME
        );
    }

    /**
     * Asserts the 3 mandatory final invariants across all scenarios:
     * 1. activeAdministratorCount == number of accounts with platformAuthority in Mongo
     * 2. No account with platformAuthority is INACTIVE
     * 3. Number of platform audit log entries == number of successful transitions
     */
    private void assertFinalInvariants(long expectedSuccessfulTransitions) {
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state, "Platform authority state must exist");

        Query authAccountsQuery = Query.query(Criteria.where("platformAuthority").is("ADMINISTRATOR"));
        long actualAdminsInDb = mongoTemplate.count(authAccountsQuery, "accounts");
        assertEquals(state.getActiveAdministratorCount(), actualAdminsInDb,
                "activeAdministratorCount must equal number of accounts with platformAuthority in Mongo");

        Query inactiveWithAuthQuery = Query.query(
                Criteria.where("platformAuthority").is("ADMINISTRATOR")
                        .and("status").is("INACTIVE")
        );
        long inactiveAdmins = mongoTemplate.count(inactiveWithAuthQuery, "accounts");
        assertEquals(0L, inactiveAdmins, "No account with platformAuthority must be INACTIVE");

        Query platformAuditQuery = Query.query(
                Criteria.where("action").in(
                        AuditAction.PLATFORM_AUTHORITY_GRANTED.name(),
                        AuditAction.PLATFORM_AUTHORITY_REVOKED.name()
                )
        );
        long platformAuditCount = mongoTemplate.count(platformAuditQuery, "identity_audit_log");
        assertEquals(expectedSuccessfulTransitions, platformAuditCount,
                "Number of platform audit log entries must equal successful transitions");
    }

    private void executeConcurrent(Runnable task1, Runnable task2) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    task1.run();
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    task2.run();
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Both tasks must be ready to run");
            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Both tasks must finish within timeout");
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Escenario a: REVOKE(A) por B || REVOKE(B) por A
    // =========================================================================
    @Test
    void concurrency_scenario_a_concurrentRevokeOnEachOther() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdB = AccountId.generate();
        seedAccount(accountIdB, "adminB@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalB = createPrincipal(accountIdB, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(2, 1);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario a", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(platformAuthorityStatePort).exists();

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        revokePlatformAuthorityService.revokePlatformAuthority(principalB, accountIdA);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        revokePlatformAuthorityService.revokePlatformAuthority(principalA, accountIdB);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron antes de que ninguna escriba
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Hubo colisión real de concurrencia
        assertTrue(retryHelper.getRetryCount() - initialRetryCount >= 1, "Retry count must increase");

        // 3. Resultado permitido: exactamente uno con éxito; el otro LastPlatformAdministratorException o InsufficientPlatformAuthorityException
        boolean task1Success = error1.get() == null;
        boolean task2Success = error2.get() == null;
        assertTrue(task1Success ^ task2Success, "Exactly one revoke must succeed");

        Throwable failedError = task1Success ? error2.get() : error1.get();
        assertTrue(
                failedError instanceof LastPlatformAdministratorException ||
                failedError instanceof InsufficientPlatformAuthorityException ||
                failedError instanceof IdentityConcurrentModificationException,
                "Failed error must be LastPlatformAdministratorException, InsufficientPlatformAuthorityException or IdentityConcurrentModificationException but was: " + failedError
        );

        // 4. Invariantes finales
        assertFinalInvariants(1L);
        assertEquals(1L, getPlatformAuthorityState().getActiveAdministratorCount());
    }

    // =========================================================================
    // Escenario b: GRANT(C) por A || GRANT(C) por A
    // =========================================================================
    @Test
    void concurrency_scenario_b_concurrentGrantOnSameAccount() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdC = AccountId.generate();
        seedAccount(accountIdC, "userC@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(1, 1);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            AccountId targetId = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (targetId.equals(accountIdC) && firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario b", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(accountRepository).findById(any(AccountId.class));

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        grantPlatformAuthorityService.grantPlatformAuthority(principalA, accountIdC);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        grantPlatformAuthorityService.grantPlatformAuthority(principalA, accountIdC);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Hubo colisión real de concurrencia
        assertTrue(retryHelper.getRetryCount() - initialRetryCount >= 1, "Retry count must increase");

        // 3. Resultado permitido: exactamente uno con éxito; el otro PlatformAuthorityAlreadyGrantedException
        boolean task1Success = error1.get() == null;
        boolean task2Success = error2.get() == null;
        assertTrue(task1Success ^ task2Success, "Exactly one grant must succeed");

        Throwable failedError = task1Success ? error2.get() : error1.get();
        assertTrue(
                failedError instanceof PlatformAuthorityAlreadyGrantedException ||
                failedError instanceof IdentityConcurrentModificationException,
                "Failed error must be PlatformAuthorityAlreadyGrantedException or IdentityConcurrentModificationException but was: " + failedError
        );

        // 4. Invariantes finales
        assertFinalInvariants(1L);
        assertEquals(2L, getPlatformAuthorityState().getActiveAdministratorCount());
        assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdC).getPlatformAuthority());
    }

    // =========================================================================
    // Escenario c: GRANT(C) por A || REVOKE(C) por B (Determinista)
    // =========================================================================
    @Test
    void concurrency_scenario_c_grantAndRevokeOnSameAccount_deterministic() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdB = AccountId.generate();
        seedAccount(accountIdB, "adminB@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalB = createPrincipal(accountIdB, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdC = AccountId.generate();
        seedAccount(accountIdC, "userC@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(2, 1);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            AccountId targetId = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (targetId.equals(accountIdC) && firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario c", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(accountRepository).findById(any(AccountId.class));

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        grantPlatformAuthorityService.grantPlatformAuthority(principalA, accountIdC);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        revokePlatformAuthorityService.revokePlatformAuthority(principalB, accountIdC);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron a C antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Resultado determinista: REVOKE lanza PlatformAuthorityNotHeldException sin escribir y GRANT tiene éxito
        assertNull(error1.get(), "GRANT must succeed");
        assertNotNull(error2.get(), "REVOKE must fail");
        assertInstanceOf(PlatformAuthorityNotHeldException.class, error2.get());

        // 3. Invariantes finales (count=3, 1 auditoría)
        assertFinalInvariants(1L);
        assertEquals(3L, getPlatformAuthorityState().getActiveAdministratorCount());
        assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdC).getPlatformAuthority());
    }

    // =========================================================================
    // Escenario d: GRANT(C) por A || DeactivateAccount(C)
    // =========================================================================
    @Test
    void concurrency_scenario_d_grantAndDeactivateAccount() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdC = AccountId.generate();
        seedAccount(accountIdC, "userC@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(1, 1);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            AccountId targetId = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (targetId.equals(accountIdC) && firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario d", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(accountRepository).findById(any(AccountId.class));

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        grantPlatformAuthorityService.grantPlatformAuthority(principalA, accountIdC);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        deactivateAccountService.deactivateAccount(new AuditActor.AccountAuditActor(accountIdA), accountIdC);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron a C antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Conflicto de escritura real en Mongo sobre documento C
        assertTrue(retryHelper.getRetryCount() - initialRetryCount >= 1, "Write conflict on account C must trigger retry");

        // 3. Resultados permitidos:
        boolean grantSuccess = error1.get() == null;
        boolean deactivateSuccess = error2.get() == null;
        assertTrue(grantSuccess ^ deactivateSuccess, "Exactly one operation must succeed");

        if (grantSuccess) {
            // GRANT ganó: C quedó como ADMINISTRATOR. DEACTIVATE falló
            assertTrue(
                    error2.get() instanceof PlatformAdministratorDeactivationException ||
                    error2.get() instanceof IdentityConcurrentModificationException,
                    "Failed error for DEACTIVATE must be PlatformAdministratorDeactivationException or IdentityConcurrentModificationException but was: " + error2.get()
            );
            assertEquals(2L, getPlatformAuthorityState().getActiveAdministratorCount());
            assertEquals(AccountStatus.ACTIVE, accountRepository.findById(accountIdC).getStatus());
            assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdC).getPlatformAuthority());
            assertFinalInvariants(1L);
        } else {
            // DEACTIVATE ganó: C quedó INACTIVE. GRANT falló
            assertTrue(
                    error1.get() instanceof InactiveAccountException ||
                    error1.get() instanceof IdentityConcurrentModificationException,
                    "Failed error for GRANT must be InactiveAccountException or IdentityConcurrentModificationException but was: " + error1.get()
            );
            assertEquals(1L, getPlatformAuthorityState().getActiveAdministratorCount());
            assertEquals(AccountStatus.INACTIVE, accountRepository.findById(accountIdC).getStatus());
            assertNull(accountRepository.findById(accountIdC).getPlatformAuthority());
            assertFinalInvariants(0L);
        }
    }

    // =========================================================================
    // Escenario e: REVOKE(B) por A || DeactivateAccount(B) (Determinista)
    // =========================================================================
    @Test
    void concurrency_scenario_e_revokeAndDeactivateAccount_deterministic() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdB = AccountId.generate();
        seedAccount(accountIdB, "adminB@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);

        seedPlatformAuthorityState(2, 1);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            AccountId targetId = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if (targetId.equals(accountIdB) && firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario e", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(accountRepository).findById(any(AccountId.class));

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        revokePlatformAuthorityService.revokePlatformAuthority(principalA, accountIdB);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        deactivateAccountService.deactivateAccount(new AuditActor.AccountAuditActor(accountIdA), accountIdB);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron a B con autoridad antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Resultado determinista: DeactivateAccount lanza PlatformAdministratorDeactivationException y REVOKE tiene éxito
        assertNull(error1.get(), "REVOKE must succeed");
        assertNotNull(error2.get(), "DeactivateAccount must fail");
        assertInstanceOf(PlatformAdministratorDeactivationException.class, error2.get());

        // 3. Invariantes finales (B ACTIVE sin autoridad, count=1, 1 auditoría)
        Account accountBInDb = accountRepository.findById(accountIdB);
        assertEquals(AccountStatus.ACTIVE, accountBInDb.getStatus(), "Account B must remain ACTIVE");
        assertNull(accountBInDb.getPlatformAuthority(), "Account B authority must be revoked");

        assertFinalInvariants(1L);
        assertEquals(1L, getPlatformAuthorityState().getActiveAdministratorCount());
    }

    // =========================================================================
    // Escenario f (D6e): REVOKE(B) por A || GRANT(C) por B
    // =========================================================================
    @Test
    void concurrency_scenario_f_d6e_revokeAndGrantConcurrent() throws InterruptedException {
        AccountId accountIdA = AccountId.generate();
        seedAccount(accountIdA, "adminA@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalA = createPrincipal(accountIdA, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdB = AccountId.generate();
        seedAccount(accountIdB, "adminB@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principalB = createPrincipal(accountIdB, PlatformAuthority.ADMINISTRATOR);

        AccountId accountIdC = AccountId.generate();
        seedAccount(accountIdC, "userC@example.com", AccountStatus.ACTIVE, null);

        seedPlatformAuthorityState(2, 1);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstPass.get()) {
                barrierArrivals.incrementAndGet();
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed in scenario f", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(platformAuthorityStatePort).exists();

        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    try {
                        revokePlatformAuthorityService.revokePlatformAuthority(principalA, accountIdB);
                    } catch (Throwable t) {
                        error1.set(t);
                    }
                },
                () -> {
                    try {
                        grantPlatformAuthorityService.grantPlatformAuthority(principalB, accountIdC);
                    } catch (Throwable t) {
                        error2.set(t);
                    }
                }
        );

        // 1. Ambas transacciones validaron a sus llamadores y leyeron el singleton antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Conflicto de escritura real sobre el documento singleton platform_authority_state
        assertTrue(retryHelper.getRetryCount() - initialRetryCount >= 1, "Conflict on singleton must trigger retry");

        // 3. Resultados permitidos:
        if (error1.get() == null && error2.get() != null) {
            // REVOKE confirmó primero: B fue revocado. GRANT en su reintento detectó por D6e que B ya no tiene autoridad (o agotó reintentos)
            assertTrue(
                    error2.get() instanceof InsufficientPlatformAuthorityException ||
                    error2.get() instanceof IdentityConcurrentModificationException,
                    "Failed error for GRANT must be InsufficientPlatformAuthorityException or IdentityConcurrentModificationException but was: " + error2.get()
            );
            assertNull(accountRepository.findById(accountIdC).getPlatformAuthority(), "Account C must NOT be granted administrator");
            assertEquals(1L, getPlatformAuthorityState().getActiveAdministratorCount());
            assertFinalInvariants(1L);
        } else if (error1.get() == null && error2.get() == null) {
            // GRANT confirmó primero: C quedó como administrador (count=3); luego REVOKE reintentó y revocó a B (count=2)
            assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdC).getPlatformAuthority());
            assertNull(accountRepository.findById(accountIdB).getPlatformAuthority());
            assertEquals(2L, getPlatformAuthorityState().getActiveAdministratorCount());
            assertFinalInvariants(2L);
        } else if (error1.get() != null && error2.get() == null) {
            // GRANT confirmó primero; REVOKE agotó sus reintentos por conflicto
            assertInstanceOf(IdentityConcurrentModificationException.class, error1.get());
            assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdC).getPlatformAuthority());
            assertEquals(PlatformAuthority.ADMINISTRATOR, accountRepository.findById(accountIdB).getPlatformAuthority());
            assertEquals(3L, getPlatformAuthorityState().getActiveAdministratorCount());
            assertFinalInvariants(1L);
        } else {
            fail("At least one operation must succeed in scenario f");
        }
    }
}
