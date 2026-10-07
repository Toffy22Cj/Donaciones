package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.InvalidVerificationTransitionException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlatformAuthority;
import identity.domain.model.VerificationCommand;
import identity.domain.model.VerificationStatus;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
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
        MongoOrganizationRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoTransactionRetryHelper.class,
        PlatformAuthorizationPolicy.class,
        AuthorizationAuditActorMapper.class,
        CreateOrganizationService.class,
        AddEmployeeService.class,
        VerifyOrganizationService.class,
        RejectOrganizationService.class,
        RequestOrganizationInformationService.class
})
@DirtiesContext
class OrganizationVerificationConcurrencyIntegrationTest extends BaseMongoIntegrationTest {

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private OrganizationRepositoryPort organizationRepository;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private CreateOrganizationService createOrganizationService;

    @Autowired
    private AddEmployeeService addEmployeeService;

    @Autowired
    private VerifyOrganizationService verifyOrganizationService;

    @Autowired
    private RejectOrganizationService rejectOrganizationService;

    @Autowired
    private RequestOrganizationInformationService requestOrganizationInformationService;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final AuditActor systemActor = new AuditActor.SystemAuditActor("test-runner");

    @BeforeEach
    void setUp() {
        reset(accountRepository, organizationRepository);
        mongoTemplate.dropCollection("accounts");
        mongoTemplate.dropCollection("organizations");
        mongoTemplate.dropCollection("identity_audit_log");
    }

    private Account seedAccount(AccountId accountId, String email, AccountStatus status, PlatformAuthority authority) {
        Account account = Account.reconstitute(
                accountId,
                new Email(email),
                new PasswordHash("hash"),
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

    private OrganizationId seedOrganization(String status, String message) {
        AccountId repId = AccountId.generate();
        seedAccount(repId, "rep-" + repId.value() + "@example.com", AccountStatus.ACTIVE, null);

        Organization created = createOrganizationService.createOrganization(systemActor, OrganizationType.COMPANY, repId);
        OrganizationId organizationId = created.getOrganizationId();

        if (status != null && !VerificationStatus.PENDING_VERIFICATION.name().equals(status)) {
            Update update = new Update().set("verificationStatus", status);
            if (message != null) {
                update.set("verificationInformationRequest", message);
            } else {
                update.unset("verificationInformationRequest");
            }
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(organizationId.value())),
                    update,
                    "organizations"
            );
        }
        // Remove ORGANIZATION_CREATED audit entry so test tracks only actions of the concurrency run
        mongoTemplate.remove(Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())), "identity_audit_log");
        return organizationId;
    }

    private List<String> getAuditActionsForOrganization(OrganizationId organizationId) {
        return mongoTemplate.find(
                Query.query(Criteria.where("targetOrganizationId").is(organizationId.value())),
                Document.class,
                "identity_audit_log"
        ).stream()
                .sorted((d1, d2) -> d1.getString("_id").compareTo(d2.getString("_id")))
                .map(d -> d.getString("action"))
                .toList();
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
    // Escenario a: PENDING || Ganador: VERIFY | Perdedor: REJECT
    // =========================================================================
    @Test
    void concurrency_scenario_a_verify_then_reject() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.a@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganization("PENDING_VERIFICATION", null);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario a");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        verifyOrganizationService.verifyOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        rejectOrganizationService.rejectOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNotNull(loserError.get(), "Loser must fail");
        assertInstanceOf(InvalidVerificationTransitionException.class, loserError.get());
        InvalidVerificationTransitionException transitionEx = (InvalidVerificationTransitionException) loserError.get();
        assertEquals(VerificationStatus.VERIFIED, transitionEx.getCurrentStatus());
        assertEquals(VerificationCommand.REJECT, transitionEx.getCommand());

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        assertEquals(List.of(AuditAction.ORGANIZATION_VERIFIED.name()), getAuditActionsForOrganization(organizationId));
    }

    // =========================================================================
    // Escenario b1: NEEDS(msg1) || Ganador: REQUEST(msg2) | Perdedor: VERIFY
    // =========================================================================
    @Test
    void concurrency_scenario_b1_requestInformation_then_verify_bothSucceed() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.b1@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganization("NEEDS_MORE_INFORMATION", "msg1");
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario b1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, "msg2");
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        verifyOrganizationService.verifyOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNull(loserError.get(), "Loser must succeed on retry");

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        assertEquals(
                List.of(AuditAction.ORGANIZATION_INFORMATION_REQUESTED.name(), AuditAction.ORGANIZATION_VERIFIED.name()),
                getAuditActionsForOrganization(organizationId)
        );
    }

    // =========================================================================
    // Escenario b2: NEEDS(msg1) || Ganador: VERIFY | Perdedor: REQUEST(msg2)
    // =========================================================================
    @Test
    void concurrency_scenario_b2_verify_then_requestInformation() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.b2@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganization("NEEDS_MORE_INFORMATION", "msg1");
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario b2");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        verifyOrganizationService.verifyOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, "msg2");
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNotNull(loserError.get(), "Loser must fail");
        assertInstanceOf(InvalidVerificationTransitionException.class, loserError.get());
        InvalidVerificationTransitionException transitionEx = (InvalidVerificationTransitionException) loserError.get();
        assertEquals(VerificationStatus.VERIFIED, transitionEx.getCurrentStatus());
        assertEquals(VerificationCommand.REQUEST_INFORMATION, transitionEx.getCommand());

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        assertEquals(List.of(AuditAction.ORGANIZATION_VERIFIED.name()), getAuditActionsForOrganization(organizationId));
    }

    // =========================================================================
    // Escenario c1: NEEDS(msg1) || Ganador: REQUEST(msg2) | Perdedor: REJECT
    // =========================================================================
    @Test
    void concurrency_scenario_c1_requestInformation_then_reject_bothSucceed() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.c1@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganization("NEEDS_MORE_INFORMATION", "msg1");
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario c1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, "msg2");
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        rejectOrganizationService.rejectOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNull(loserError.get(), "Loser must succeed on retry");

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("REJECTED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        assertEquals(
                List.of(AuditAction.ORGANIZATION_INFORMATION_REQUESTED.name(), AuditAction.ORGANIZATION_REJECTED.name()),
                getAuditActionsForOrganization(organizationId)
        );
    }

    // =========================================================================
    // Escenario c2: NEEDS(msg1) || Ganador: REJECT | Perdedor: REQUEST(msg2)
    // =========================================================================
    @Test
    void concurrency_scenario_c2_reject_then_requestInformation() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.c2@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        OrganizationId organizationId = seedOrganization("NEEDS_MORE_INFORMATION", "msg1");
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario c2");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        rejectOrganizationService.rejectOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        requestOrganizationInformationService.requestOrganizationInformation(principal, organizationId, "msg2");
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNotNull(loserError.get(), "Loser must fail");
        assertInstanceOf(InvalidVerificationTransitionException.class, loserError.get());
        InvalidVerificationTransitionException transitionEx = (InvalidVerificationTransitionException) loserError.get();
        assertEquals(VerificationStatus.REJECTED, transitionEx.getCurrentStatus());
        assertEquals(VerificationCommand.REQUEST_INFORMATION, transitionEx.getCommand());

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("REJECTED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        assertEquals(List.of(AuditAction.ORGANIZATION_REJECTED.name()), getAuditActionsForOrganization(organizationId));
    }

    // =========================================================================
    // Escenario d1: PENDING || Ganador: addEmployee(E) | Perdedor: VERIFY
    // =========================================================================
    @Test
    void concurrency_scenario_d1_addEmployee_then_verify_bothSucceed() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.d1@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        AccountId employeeId = AccountId.generate();
        seedAccount(employeeId, "emp.d1@example.com", AccountStatus.ACTIVE, null);

        OrganizationId organizationId = seedOrganization("PENDING_VERIFICATION", null);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario d1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        addEmployeeService.addEmployee(new AuditActor.AccountAuditActor(adminId), organizationId, employeeId);
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        verifyOrganizationService.verifyOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNull(loserError.get(), "Loser must succeed on retry");

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> members = rawDoc.getList("members", Document.class);
        assertNotNull(members);
        assertTrue(members.stream().anyMatch(m -> employeeId.value().equals(m.getString("accountId"))), "Employee must be member");

        Account employeeAccount = accountRepository.findById(employeeId);
        assertEquals(organizationId, employeeAccount.getOrganizationId(), "Employee account must belong to organization");

        List<String> actions = getAuditActionsForOrganization(organizationId);
        assertEquals(2, actions.size());
        assertEquals(List.of(AuditAction.EMPLOYEE_ADDED.name(), AuditAction.ORGANIZATION_VERIFIED.name()), actions);
    }

    // =========================================================================
    // Escenario d2: PENDING || Ganador: VERIFY | Perdedor: addEmployee(E)
    // =========================================================================
    @Test
    void concurrency_scenario_d2_verify_then_addEmployee_bothSucceed() throws InterruptedException {
        AccountId adminId = AccountId.generate();
        seedAccount(adminId, "admin.d2@example.com", AccountStatus.ACTIVE, PlatformAuthority.ADMINISTRATOR);
        AuthorizationPrincipal principal = createPrincipal(adminId, PlatformAuthority.ADMINISTRATOR);

        AccountId employeeId = AccountId.generate();
        seedAccount(employeeId, "emp.d2@example.com", AccountStatus.ACTIVE, null);

        OrganizationId organizationId = seedOrganization("PENDING_VERIFICATION", null);
        int initialRetryCount = retryHelper.getRetryCount();

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);
        AtomicInteger barrierArrivals = new AtomicInteger(0);

        doAnswer(firstPassBarrier(readBarrier, firstPass, barrierArrivals))
                .when(organizationRepository).findById(organizationId);

        CountDownLatch winnerDone = new CountDownLatch(1);
        AtomicReference<Thread> winnerThread = new AtomicReference<>();
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean loserFirstSave = new AtomicBoolean(true);
        AtomicBoolean loserFirstSaveFailed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (current.equals(loserThread.get()) && loserFirstSave.compareAndSet(true, false)) {
                try {
                    if (!winnerDone.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("winnerDone latch timed out after 5s in scenario d2");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for winnerDone", e);
                }
                try {
                    return invocation.callRealMethod();
                } catch (Throwable t) {
                    loserFirstSaveFailed.set(true);
                    throw t;
                }
            }
            return invocation.callRealMethod();
        }).when(organizationRepository).save(any(Organization.class));

        AtomicReference<Throwable> winnerError = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();

        executeConcurrent(
                () -> {
                    winnerThread.set(Thread.currentThread());
                    try {
                        verifyOrganizationService.verifyOrganization(principal, organizationId);
                    } catch (Throwable t) {
                        winnerError.set(t);
                    } finally {
                        winnerDone.countDown();
                    }
                },
                () -> {
                    loserThread.set(Thread.currentThread());
                    try {
                        addEmployeeService.addEmployee(new AuditActor.AccountAuditActor(adminId), organizationId, employeeId);
                    } catch (Throwable t) {
                        loserError.set(t);
                    }
                }
        );

        assertEquals(2, barrierArrivals.get(), "Both transactions must read through findById before either saves");
        assertNull(winnerError.get(), "Winner must succeed");
        assertNull(loserError.get(), "Loser must succeed on retry");

        assertTrue(loserFirstSaveFailed.get(), "Loser's first save must fail with WriteConflict");
        int retryDelta = retryHelper.getRetryCount() - initialRetryCount;
        assertEquals(1, retryDelta, "Retry delta must be 1");

        Document rawDoc = mongoTemplate.findById(organizationId.value(), Document.class, "organizations");
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));
        assertNull(rawDoc.getString("verificationInformationRequest"));

        List<Document> members = rawDoc.getList("members", Document.class);
        assertNotNull(members);
        assertTrue(members.stream().anyMatch(m -> employeeId.value().equals(m.getString("accountId"))), "Employee must be member");

        Account employeeAccount = accountRepository.findById(employeeId);
        assertEquals(organizationId, employeeAccount.getOrganizationId(), "Employee account must belong to organization");

        List<String> actions = getAuditActionsForOrganization(organizationId);
        assertEquals(2, actions.size());
        assertEquals(List.of(AuditAction.ORGANIZATION_VERIFIED.name(), AuditAction.EMPLOYEE_ADDED.name()), actions);
    }
}
