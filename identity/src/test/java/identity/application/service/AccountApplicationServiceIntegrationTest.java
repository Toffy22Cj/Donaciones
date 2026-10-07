package identity.application.service;

import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.test.context.ContextConfiguration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        BCryptPasswordHasherAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateAccountService.class,
        ChangeCredentialsService.class,
        DeactivateAccountService.class,
        ReactivateAccountService.class,
        AddEmployeeService.class
})
class AccountApplicationServiceIntegrationTest extends BaseMongoIntegrationTest {

    private final AuditActor testActor = new AuditActor.AccountAuditActor(AccountId.generate());

    @Autowired
    private CreateAccountService createAccountService;

    @Autowired
    private ChangeCredentialsService changeCredentialsService;

    @Autowired
    private DeactivateAccountService deactivateAccountService;

    @Autowired
    private ReactivateAccountService reactivateAccountService;

    @Autowired
    private AddEmployeeService addEmployeeService;

    @Autowired
    private OrganizationRepositoryPort organizationRepository;

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @Autowired
    private PasswordHasherPort passwordHasher;

    @MockitoSpyBean
    private AuditLogPort auditLogPort;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Test
    void createAccount_success() {
        Email email = new Email("new.account@example.com");
        String password = "securePassword123";

        Account account = createAccountService.createAccount(email, password);

        assertNotNull(account.getAccountId());
        assertEquals(email, account.getEmail());
        assertTrue(passwordHasher.matches(password, account.getPasswordHash()));

        // Verification of Audit Log invocation
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void createAccount_throwsDuplicateEmailException_andDoesNotGenerateAuditLog() {
        Email email = new Email("duplicate.test@example.com");
        String password = "password123-long";

        // Create first account
        createAccountService.createAccount(email, password);
        // Reset spy to clear the first creation's audit log invocation
        org.mockito.Mockito.reset(auditLogPort);

        // Attempt to create second account with same email
        assertThrows(DuplicateEmailException.class, () -> {
            createAccountService.createAccount(email, "anotherPassword");
        });

        // CRITICAL REQUIREMENT: Explicit negative assertion
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void changeCredentials_success() {
        Email email = new Email("change.creds@example.com");
        Account account = createAccountService.createAccount(email, "oldPassword-123");
        org.mockito.Mockito.reset(auditLogPort);

        changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "newPassword-123");

        Account updated = accountRepository.findById(account.getAccountId());
        assertTrue(passwordHasher.matches("newPassword-123", updated.getPasswordHash()));
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void shortPasswords_areRejected_onCreationAndOnChange_withoutWritingAnything() {
        // H-P2-1 (Carlos, 2026-10-07): al menos 12 caracteres
        Email email = new Email("short.password@example.com");
        org.mockito.Mockito.reset(auditLogPort);
        assertThrows(identity.domain.exception.PasswordTooShortException.class,
                () -> createAccountService.createAccount(email, "elevenchars"));
        assertTrue(accountRepository.findByEmail(email).isEmpty());

        Account account = createAccountService.createAccount(email, "twelve-chars");
        org.mockito.Mockito.reset(auditLogPort);
        assertThrows(identity.domain.exception.PasswordTooShortException.class,
                () -> changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "short"));
        assertTrue(passwordHasher.matches("twelve-chars", accountRepository.findById(account.getAccountId()).getPasswordHash()));
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void changeCredentials_onInactiveAccount_throwsException_andDoesNotGenerateAuditLog() {
        Email email = new Email("change.creds.inactive@example.com");
        Account account = createAccountService.createAccount(email, "oldPassword-123");
        deactivateAccountService.deactivateAccount(testActor, account.getAccountId());
        org.mockito.Mockito.reset(auditLogPort);

        assertThrows(identity.domain.exception.InactiveAccountException.class, () -> {
            changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "newPassword-123");
        });

        verify(auditLogPort, never()).record(any());
    }

    @Test
    void deactivateAccount_success() {
        Email email = new Email("deactivate@example.com");
        Account account = createAccountService.createAccount(email, "password-12345");
        org.mockito.Mockito.reset(auditLogPort);

        deactivateAccountService.deactivateAccount(testActor, account.getAccountId());

        Account updated = accountRepository.findById(account.getAccountId());
        assertEquals(AccountStatus.INACTIVE, updated.getStatus());
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void deactivateAccount_idempotent_doesNotGenerateAuditLog() {
        Email email = new Email("deactivate.idempotent@example.com");
        Account account = createAccountService.createAccount(email, "password-12345");
        deactivateAccountService.deactivateAccount(testActor, account.getAccountId()); // First time mutates
        org.mockito.Mockito.reset(auditLogPort);

        // Second time should be idempotent
        deactivateAccountService.deactivateAccount(testActor, account.getAccountId());

        // CRITICAL REQUIREMENT: Explicit negative assertion on idempotent call
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void reactivateAccount_success() {
        Email email = new Email("reactivate@example.com");
        Account account = createAccountService.createAccount(email, "password-12345");
        deactivateAccountService.deactivateAccount(testActor, account.getAccountId());
        org.mockito.Mockito.reset(auditLogPort);

        reactivateAccountService.reactivateAccount(testActor, account.getAccountId());

        Account updated = accountRepository.findById(account.getAccountId());
        assertEquals(AccountStatus.ACTIVE, updated.getStatus());
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void reactivateAccount_idempotent_doesNotGenerateAuditLog() {
        Email email = new Email("reactivate.idempotent@example.com");
        Account account = createAccountService.createAccount(email, "password-12345");
        org.mockito.Mockito.reset(auditLogPort);

        // Already active, so reactivating should be idempotent
        reactivateAccountService.reactivateAccount(testActor, account.getAccountId());

        // CRITICAL REQUIREMENT: Explicit negative assertion on idempotent call
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void concurrentChangeCredentials_sameAccount_bothSucceedAfterRetry() throws InterruptedException {
        Account account = createAccountService.createAccount(new Email("concurrent.creds@example.com"), "initialPassword");

        int initialRetries = retryHelper.getRetryCount();
        String password1 = "newPasswordAlpha123";
        String password2 = "newPasswordBeta456";

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        AtomicBoolean firstPass = new AtomicBoolean(true);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstPass.get()) {
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // Ignorar si hay timeout en la barrera
                }
                if (readBarrier.getNumberWaiting() == 0) {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(accountRepository).findById(account.getAccountId());

        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable task1 = () -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    changeCredentialsService.changeCredentials(testActor, account.getAccountId(), password1);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            };

            Runnable task2 = () -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    changeCredentialsService.changeCredentials(testActor, account.getAccountId(), password2);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    doneLatch.countDown();
                }
            };

            executorService.submit(task1);
            executorService.submit(task2);

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Timeout: los hilos no estuvieron listos");
            startLatch.countDown();

            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Timeout: los hilos concurrentes no terminaron");

            if (error.get() != null) {
                System.err.println("=== Detalle de Excepción en concurrentChangeCredentials ===");
                Throwable cur = error.get();
                while (cur != null) {
                    System.err.println("  -> " + cur.getClass().getName() + ": " + cur.getMessage());
                    if (cur instanceof com.mongodb.MongoException me) {
                        System.err.println("     errorLabels: " + me.getErrorLabels());
                    }
                    cur = cur.getCause();
                }
                fail("Concurrency test failed with exception: " + error.get().getMessage(), error.get());
            }

            Account updated = accountRepository.findById(account.getAccountId());
            boolean p1Matches = passwordHasher.matches(password1, updated.getPasswordHash());
            boolean p2Matches = passwordHasher.matches(password2, updated.getPasswordHash());

            assertTrue(p1Matches ^ p2Matches, "Password must match exactly one of the two concurrent updates");

            assertTrue(retryHelper.getRetryCount() > initialRetries,
                    "Expected retryCount to increase from " + initialRetries + " due to write conflict retry");
        } finally {
            reset(accountRepository);
            executorService.shutdownNow();
        }
    }

    @Test
    void changeCredentials_onInactiveAccount_doesNotRetry() {
        Email email = new Email("inactive.noretry@example.com");
        Account account = createAccountService.createAccount(email, "password123-long");
        deactivateAccountService.deactivateAccount(testActor, account.getAccountId());

        int retriesBefore = retryHelper.getRetryCount();

        assertThrows(identity.domain.exception.InactiveAccountException.class, () -> {
            changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "newPassword-123");
        });

        assertEquals(retriesBefore, retryHelper.getRetryCount(), "Domain exceptions must not trigger transaction retries");
    }

    @Test
    void createAccount_returnsPersistedAccountMatchingFindById() {
        Email email = new Email("return.check@example.com");
        String password = "securePassword123";

        Account returnedAccount = createAccountService.createAccount(email, password);

        assertNotNull(returnedAccount);
        assertNotNull(returnedAccount.getAccountId());
        Account loadedAccount = accountRepository.findById(returnedAccount.getAccountId());
        assertNotNull(loadedAccount);
        assertEquals(returnedAccount.getAccountId(), loadedAccount.getAccountId());
        assertEquals(returnedAccount.getEmail(), loadedAccount.getEmail());
    }

    @Test
    void changeCredentials_insideActiveTransaction_throwsNestedIdentityTransactionException() {
        Email email = new Email("nested.tx@example.com");
        Account account = createAccountService.createAccount(email, "password123-long");

        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(identity.domain.exception.NestedIdentityTransactionException.class, () -> {
                changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "newPassword-123");
            });
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void deactivateAccount_auditActorIsNotTheTarget() {
        Email email = new Email("target.deact@example.com");
        Account targetAccount = createAccountService.createAccount(email, "password123-long");
        Account operatorAccount = createAccountService.createAccount(new Email("operator.deact@example.com"), "password123-long");

        AuditActor operatorActor = new AuditActor.AccountAuditActor(operatorAccount.getAccountId());
        deactivateAccountService.deactivateAccount(operatorActor, targetAccount.getAccountId());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("targetAccountId").is(targetAccount.getAccountId().value())
                                .and("action").is("ACCOUNT_DEACTIVATED")),
                org.bson.Document.class,
                "identity_audit_log"
        );

        assertNotNull(rawDoc, "Audit log document must exist in MongoDB");
        assertNull(rawDoc.get("actorAccountId"), "actorAccountId must not exist or must be null in post-cutover document");
        org.bson.Document actorDoc = rawDoc.get("actor", org.bson.Document.class);
        assertNotNull(actorDoc, "actor subdocument must exist");
        assertEquals("ACCOUNT", actorDoc.getString("type"));
        assertEquals(operatorAccount.getAccountId().value(), actorDoc.getString("accountId"));
    }

    @Test
    void createAccount_selfRegistration_recordsNewAccountAsActor_andSetsFlag() {
        Email email = new Email("selfreg@example.com");
        Account account = createAccountService.createAccount(email, "password123-long");

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("targetAccountId").is(account.getAccountId().value())
                                .and("action").is("ACCOUNT_CREATED")),
                org.bson.Document.class,
                "identity_audit_log"
        );

        assertNotNull(rawDoc, "Audit log document must exist");
        assertNull(rawDoc.get("actorAccountId"), "actorAccountId must be null in post-cutover");
        org.bson.Document actorDoc = rawDoc.get("actor", org.bson.Document.class);
        assertNotNull(actorDoc, "actor subdocument must exist");
        assertEquals("ACCOUNT", actorDoc.getString("type"));
        assertEquals(account.getAccountId().value(), actorDoc.getString("accountId"));

        org.bson.Document changeSummary = rawDoc.get("changeSummary", org.bson.Document.class);
        assertNotNull(changeSummary, "changeSummary subdocument must exist");
        assertEquals(Boolean.TRUE, changeSummary.getBoolean("selfRegistration"));
    }

    @Test
    void deactivateAccount_onPlatformAdministrator_isRejected_andAccountIntact() {
        Email email = new Email("admin.deact@example.com");
        Account account = createAccountService.createAccount(email, "password123-long");

        mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(account.getAccountId().value())),
                new org.springframework.data.mongodb.core.query.Update().set("platformAuthority", "ADMINISTRATOR"),
                "accounts"
        );

        reset(auditLogPort);
        int retriesBefore = retryHelper.getRetryCount();

        assertThrows(identity.domain.exception.PlatformAdministratorDeactivationException.class, () -> {
            deactivateAccountService.deactivateAccount(testActor, account.getAccountId());
        });

        assertEquals(retriesBefore, retryHelper.getRetryCount(), "Domain exceptions must not trigger transaction retries");
        verify(auditLogPort, never()).record(any());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(account.getAccountId().value())),
                org.bson.Document.class,
                "accounts"
        );

        assertNotNull(rawDoc);
        assertEquals("ACTIVE", rawDoc.getString("status"));
        assertEquals("ADMINISTRATOR", rawDoc.getString("platformAuthority"));
    }

    @Test
    void changeCredentials_onPlatformAdministrator_preservesPlatformAuthority() {
        Email email = new Email("admin.changecred@example.com");
        Account account = createAccountService.createAccount(email, "password123-long");

        mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(account.getAccountId().value())),
                new org.springframework.data.mongodb.core.query.Update().set("platformAuthority", "ADMINISTRATOR"),
                "accounts"
        );

        changeCredentialsService.changeCredentials(testActor, account.getAccountId(), "newPassword456");

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(account.getAccountId().value())),
                org.bson.Document.class,
                "accounts"
        );

        assertNotNull(rawDoc);
        assertEquals("ADMINISTRATOR", rawDoc.getString("platformAuthority"));
    }

    @Test
    void addEmployee_onPlatformAdministrator_preservesPlatformAuthority() {
        Account rep = createAccountService.createAccount(new Email("rep.org@example.com"), "password123-long");
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, rep.getAccountId());
        organizationRepository.save(organization);
        rep.joinOrganization(organization.getOrganizationId());
        accountRepository.save(rep);

        Account adminAccount = createAccountService.createAccount(new Email("admin.employee@example.com"), "password123-long");

        mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(adminAccount.getAccountId().value())),
                new org.springframework.data.mongodb.core.query.Update().set("platformAuthority", "ADMINISTRATOR"),
                "accounts"
        );

        addEmployeeService.addEmployee(testActor, organization.getOrganizationId(), adminAccount.getAccountId());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(adminAccount.getAccountId().value())),
                org.bson.Document.class,
                "accounts"
        );

        assertNotNull(rawDoc);
        assertEquals("ADMINISTRATOR", rawDoc.getString("platformAuthority"));
    }
}
