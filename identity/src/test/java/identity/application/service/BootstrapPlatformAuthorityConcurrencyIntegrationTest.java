package identity.application.service;

import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.PlatformAlreadyBootstrappedException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
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
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
        BootstrapPlatformAuthorityService.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class
})
@DirtiesContext
class BootstrapPlatformAuthorityConcurrencyIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private PlatformAuthorityStatePort platformAuthorityStatePort;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private BootstrapPlatformAuthorityService bootstrapService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        reset(accountRepository, platformAuthorityStatePort);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("identity_audit_log");
        mongoTemplate.dropCollection("platform_authority_state");
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

    private PlatformAuthorityStateDocument getPlatformAuthorityState() {
        return mongoTemplate.findById(
                MongoPlatformAuthorityStateAdapter.SINGLETON_ID,
                PlatformAuthorityStateDocument.class,
                MongoPlatformAuthorityStateAdapter.COLLECTION_NAME
        );
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

    private Answer<Object> firstPassBarrier(CyclicBarrier barrier, AtomicBoolean firstPass, AtomicInteger arrivals) {
        return invocation -> {
            Object result = invocation.callRealMethod();
            if (firstPass.get()) {
                arrivals.incrementAndGet();
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("Barrier await failed", e);
                } finally {
                    firstPass.set(false);
                }
            }
            return result;
        };
    }

    // =========================================================================
    // Orden (i): El ganador ya confirmó antes de que el perdedor escriba
    // =========================================================================
    @Test
    void concurrency_order1_winnerCommittedBeforeLoserWrites() throws InterruptedException {
        AccountId accountIdX = AccountId.generate();
        seedAccount(accountIdX, "adminX@example.com", AccountStatus.ACTIVE, null);

        AccountId accountIdY = AccountId.generate();
        seedAccount(accountIdY, "adminY@example.com", AccountStatus.ACTIVE, null);

        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(accountRepository).existsAnyWithPlatformAuthority();

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstWrite = new AtomicBoolean(true);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstWrite.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in order (i)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
            }
            return invocation.callRealMethod();
        }).when(platformAuthorityStatePort).initialize();

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        bootstrapService.bootstrap("adminX@example.com");
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        bootstrapService.bootstrap("adminY@example.com");
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Resultados exactos: ganador sin error, perdedor lanza PlatformAlreadyBootstrappedException
        assertNull(winnerError.get(), "Winner must succeed without error");
        assertNotNull(loserError.get(), "Loser must fail");

        // Observado en MongoDB 6.0 (verificado por el humano sobre 4218ffb): la inserción del singleton por el
        // perdedor da WriteConflict (TransientTransactionError) en ambos órdenes → 1 reintento → exists() → AlreadyBootstrapped.
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1 (observed: " + retryDelta + ")");
        assertInstanceOf(
                PlatformAlreadyBootstrappedException.class,
                loserError.get(),
                "Loser must fail with PlatformAlreadyBootstrappedException (retryDelta=" + retryDelta + ")"
        );

        // 3. Invariantes finales
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state, "Platform authority state must exist");
        assertEquals(1L, state.getActiveAdministratorCount(), "Active administrator count must be 1");
        assertEquals(1L, state.getVersion(), "Version must be 1");

        Account accountX = accountRepository.findById(accountIdX);
        assertEquals(PlatformAuthority.ADMINISTRATOR, accountX.getPlatformAuthority(), "Account X must be administrator");

        Account accountY = accountRepository.findById(accountIdY);
        assertNull(accountY.getPlatformAuthority(), "Account Y must NOT have platform authority");

        Query auditQuery = Query.query(Criteria.where("action").is(AuditAction.BOOTSTRAP_PLATFORM_AUTHORITY.name()));
        long auditCount = mongoTemplate.count(auditQuery, "identity_audit_log");
        assertEquals(1L, auditCount, "Exactly 1 bootstrap audit log entry must exist");
    }

    // =========================================================================
    // Orden (ii): El ganador insertó pero no ha confirmado cuando el perdedor intenta escribir
    // =========================================================================
    @Test
    void concurrency_order2_winnerInsertedBeforeLoserWrites_concurrentTransactions() throws InterruptedException {
        AccountId accountIdX = AccountId.generate();
        seedAccount(accountIdX, "adminX@example.com", AccountStatus.ACTIVE, null);

        AccountId accountIdY = AccountId.generate();
        seedAccount(accountIdY, "adminY@example.com", AccountStatus.ACTIVE, null);

        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(accountRepository).existsAnyWithPlatformAuthority();

        CountDownLatch winnerStartedTx = new CountDownLatch(1);
        CountDownLatch winnerInitialized = new CountDownLatch(1);
        CountDownLatch loserInitFinished = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);

        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstWrite = new AtomicBoolean(true);
        AtomicBoolean loserFirstGrant = new AtomicBoolean(true);
        AtomicBoolean loserFirstInitSucceeded = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserInitFinished.getCount() == 0) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in order (ii) retry");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone in retry", e);
                }
            }
            try {
                return invocation.callRealMethod();
            } finally {
                if (current.equals(winnerThread.get())) {
                    winnerStartedTx.countDown();
                }
            }
        }).when(platformAuthorityStatePort).exists();

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(winnerThread.get())) {
                Object result = invocation.callRealMethod();
                winnerInitialized.countDown();
                return result;
            } else if (current.equals(loserThread.get()) && loserFirstWrite.compareAndSet(true, false)) {
                try {
                    if (!winnerInitialized.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerInitialized latch timed out after 5s in order (ii)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerInitialized", e);
                }
                try {
                    Object result = invocation.callRealMethod();
                    loserFirstInitSucceeded.set(true);
                    return result;
                } finally {
                    loserInitFinished.countDown();
                }
            }
            return invocation.callRealMethod();
        }).when(platformAuthorityStatePort).initialize();

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(winnerThread.get())) {
                try {
                    if (!loserInitFinished.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("loserInitFinished latch timed out after 5s in order (ii)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for loserInitFinished", e);
                }
                return invocation.callRealMethod();
            } else if (current.equals(loserThread.get()) && loserFirstGrant.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s before loser's first grant in order (ii)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone before loser's first grant", e);
                }
                return invocation.callRealMethod();
            }
            return invocation.callRealMethod();
        }).when(accountRepository).grantPlatformAuthorityIfAbsent(any(AccountId.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        bootstrapService.bootstrap("adminX@example.com");
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        if (!winnerStartedTx.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("winnerStartedTx latch timed out after 5s in order (ii)");
                        }
                        bootstrapService.bootstrap("adminY@example.com");
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        // 1. Ambas transacciones leyeron antes de escribir
        assertEquals(2, barrierArrivals.get(), "Both transactions must reach the barrier before either writes");

        // 2. Resultados exactos: ganador sin error, perdedor lanza PlatformAlreadyBootstrappedException
        assertNull(winnerError.get(), "Winner must succeed without error");
        assertNotNull(loserError.get(), "Loser must fail");

        assertTrue(loserFirstInitSucceeded.get(), "Observado en MongoDB 6.0: la 2.ª inserción del singleton no falla mientras la 1.ª no confirma; el conflicto aparece al confirmar");

        // Observado en MongoDB 6.0 (instrumentado por el humano sobre 788def5): con el ganador sin confirmar,
        // la inserción del perdedor NO falla; el conflicto aparece al confirmar. El perdedor espera a que el
        // ganador confirme (winnerDone) antes de su primera escritura de cuenta → conflicto → 1 reintento →
        // exists()=true → PlatformAlreadyBootstrappedException.
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1 (observed: " + retryDelta + ")");
        assertInstanceOf(
                PlatformAlreadyBootstrappedException.class,
                loserError.get(),
                "Loser must fail with PlatformAlreadyBootstrappedException (retryDelta=" + retryDelta + ")"
        );

        // 3. Invariantes finales
        PlatformAuthorityStateDocument state = getPlatformAuthorityState();
        assertNotNull(state, "Platform authority state must exist");
        assertEquals(1L, state.getActiveAdministratorCount(), "Active administrator count must be 1");
        assertEquals(1L, state.getVersion(), "Version must be 1");

        Account accountX = accountRepository.findById(accountIdX);
        assertEquals(PlatformAuthority.ADMINISTRATOR, accountX.getPlatformAuthority(), "Account X must be administrator");

        Account accountY = accountRepository.findById(accountIdY);
        assertNull(accountY.getPlatformAuthority(), "Account Y must NOT have platform authority");

        Query auditQuery = Query.query(Criteria.where("action").is(AuditAction.BOOTSTRAP_PLATFORM_AUTHORITY.name()));
        long auditCount = mongoTemplate.count(auditQuery, "identity_audit_log");
        assertEquals(1L, auditCount, "Exactly 1 bootstrap audit log entry must exist");
    }
}
