package identity.application.service;

import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.domain.model.Role;
import identity.infrastructure.persistence.mongo.IdentityTestApplication;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationRepositoryAdapter;
// Same package as MongoTransactionRetryHelper
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        MongoOrganizationRepositoryAdapter.class,
        MongoTransactionRetryHelper.class,
        CreateOrganizationService.class,
        AddEmployeeService.class,
        AssignAdministratorService.class,
        RemoveAdministratorService.class,
        RemoveEmployeeService.class,
        RemoveMemberFromOrganizationService.class,
        TransferRepresentativeAndRemoveService.class
})
class OrganizationApplicationServiceIntegrationTest extends BaseMongoIntegrationTest {

    private final AuditActor testActor = new AuditActor.AccountAuditActor(AccountId.generate());

    @Autowired
    private CreateOrganizationService createOrganizationService;
    
    @Autowired
    private AddEmployeeService addEmployeeService;

    @Autowired
    private AssignAdministratorService assignAdministratorService;

    @Autowired
    private RemoveAdministratorService removeAdministratorService;
    
    @Autowired
    private RemoveEmployeeService removeEmployeeService;
    
    @Autowired
    private RemoveMemberFromOrganizationService removeMemberFromOrganizationService;

    @Autowired
    private TransferRepresentativeAndRemoveService transferRepresentativeAndRemoveService;

    @MockitoSpyBean
    private AccountRepositoryPort accountRepository;

    @MockitoSpyBean
    private OrganizationRepositoryPort organizationRepository;

    @Autowired
    private MongoTransactionRetryHelper retryHelper;

    @MockitoSpyBean
    private AuditLogPort auditLogPort;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        // Any setup if needed
    }

    @Test
    void testCreateOrganization_Success() {
        Account rep = Account.createAccount(new Email("rep@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);

        Organization org = createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, rep.getAccountId());

        assertNotNull(org);
        assertEquals(OrganizationType.COMPANY, org.getType());
        
        Account updatedRep = accountRepository.findById(rep.getAccountId());
        assertEquals(org.getOrganizationId(), updatedRep.getOrganizationId());

        verify(auditLogPort).record(argThat(log -> log.action() == AuditAction.ORGANIZATION_CREATED));
    }

    @Test
    void testAssignAdministrator_Idempotent_NoAuditLog() {
        Account rep = Account.createAccount(new Email("rep2@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);
        Organization org = createOrganizationService.createOrganization(testActor, OrganizationType.FOUNDATION, rep.getAccountId());

        reset(auditLogPort); // Reset audit port since createOrganization already logged

        // Re-assign representative as administrator (which is a different role, so first time it mutates)
        assignAdministratorService.assignAdministrator(testActor, org.getOrganizationId(), rep.getAccountId());
        verify(auditLogPort, times(1)).record(argThat(log -> log.action() == AuditAction.ADMINISTRATOR_ASSIGNED));

        reset(auditLogPort);

        // Assign again - should be no-op
        assignAdministratorService.assignAdministrator(testActor, org.getOrganizationId(), rep.getAccountId());
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void testDomainException_DoesNotRetry() {
        AccountId nonExistentId = AccountId.generate();

        assertThrows(AccountNotFoundException.class, () -> 
            createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, nonExistentId)
        );

        // Verify that findById was only called exactly once, meaning no retries were attempted
        verify(accountRepository, times(1)).findById(nonExistentId);
    }

    @Test
    void testConcurrency_WriteConflictIsRetried() throws InterruptedException, java.util.concurrent.BrokenBarrierException {
        Account rep = Account.createAccount(new Email("rep3@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);
        Organization org = createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, rep.getAccountId());
        
        Account emp1 = Account.createAccount(new Email("emp1@test.com"), new PasswordHash("hash"));
        Account emp2 = Account.createAccount(new Email("emp2@test.com"), new PasswordHash("hash"));
        accountRepository.save(emp1);
        accountRepository.save(emp2);

        // We want to force both threads to read the state BEFORE either writes.
        // By spying on organizationRepository.findById, we can make them wait for each other,
        // but ONLY on the first attempt. Subsequent retries shouldn't block.
        java.util.concurrent.CyclicBarrier readBarrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicBoolean firstPass = new java.util.concurrent.atomic.AtomicBoolean(true);
        
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // Both threads wait here after reading, so they both have the exact same version
            // before proceeding to modify and save, but only on their very first try
            if (firstPass.get()) {
                try {
                    readBarrier.await(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // ignore if barrier is broken or timed out
                }
                if (readBarrier.getNumberWaiting() == 0) {
                    firstPass.set(false);
                }
            }
            return result;
        }).when(organizationRepository).findById(org.getOrganizationId());

        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicReference<Throwable> error = new AtomicReference<>();

        Runnable task1 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                addEmployeeService.addEmployee(testActor, org.getOrganizationId(), emp1.getAccountId());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                addEmployeeService.addEmployee(testActor, org.getOrganizationId(), emp2.getAccountId());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                doneLatch.countDown();
            }
        };

        executorService.submit(task1);
        executorService.submit(task2);

        // Wait until both threads are ready to hit the service
        readyLatch.await(5, TimeUnit.SECONDS);
        
        // Fire them at the same time
        startLatch.countDown();

        // Wait for both to finish
        doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();
        
        // Remove the mock answer to not affect other operations
        reset(organizationRepository);

        if (error.get() != null) {
            fail("Concurrency test failed with exception: " + error.get().getMessage(), error.get());
        }

        Organization updatedOrg = organizationRepository.findById(org.getOrganizationId());
        
        // Ensure both employees were added successfully and there was no data loss
        assertEquals(3, updatedOrg.getMembers().size()); // 1 rep + 2 employees
        assertTrue(updatedOrg.getMembers().stream().anyMatch(m -> m.getAccountId().equals(emp1.getAccountId())));
        assertTrue(updatedOrg.getMembers().stream().anyMatch(m -> m.getAccountId().equals(emp2.getAccountId())));
        
        // Assert that a write conflict actually occurred and was retried
        assertTrue(retryHelper.getRetryCount() > 0, "Expected at least 1 retry due to forced TransientTransactionError write conflict");
    }

    @Test
    void testTransferRepresentativeAndRemove_NullifiesAccountOrgId() {
        Account rep = Account.createAccount(new Email("rep4@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);
        Organization org = createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, rep.getAccountId());
        
        Account successor = Account.createAccount(new Email("suc@test.com"), new PasswordHash("hash"));
        accountRepository.save(successor);
        addEmployeeService.addEmployee(testActor, org.getOrganizationId(), successor.getAccountId());

        // Now rep only has REPRESENTATIVE role, and successor is an EMPLOYEE.
        // Transferring will remove rep completely.
        transferRepresentativeAndRemoveService.transferRepresentativeAndRemove(testActor, org.getOrganizationId(), rep.getAccountId(), successor.getAccountId());

        Organization updatedOrg = organizationRepository.findById(org.getOrganizationId());
        assertEquals(1, updatedOrg.getMembers().size()); // Only successor remains
        
        Account updatedRep = accountRepository.findById(rep.getAccountId());
        assertNull(updatedRep.getOrganizationId()); // Successfully left organization

        Account updatedSuccessor = accountRepository.findById(successor.getAccountId());
        assertEquals(org.getOrganizationId(), updatedSuccessor.getOrganizationId());
    }

    @Test
    void createOrganization_persistsPendingVerificationExplicitly() {
        Account rep = Account.createAccount(new Email("rep.verify@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);

        Organization organization = createOrganizationService.createOrganization(testActor, OrganizationType.FOUNDATION, rep.getAccountId());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(organization.getOrganizationId().value())),
                org.bson.Document.class,
                "organizations"
        );
        assertNotNull(rawDoc);
        assertEquals("PENDING_VERIFICATION", rawDoc.getString("verificationStatus"));
    }

    @Test
    void addEmployee_onVerifiedOrganization_preservesVerificationStatus() {
        Account rep = Account.createAccount(new Email("rep.addemp@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);
        Organization organization = createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, rep.getAccountId());

        mongoTemplate.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(organization.getOrganizationId().value())),
                org.springframework.data.mongodb.core.query.Update.update("verificationStatus", "VERIFIED"),
                "organizations"
        );

        Account emp = Account.createAccount(new Email("emp.verify@test.com"), new PasswordHash("hash"));
        accountRepository.save(emp);

        addEmployeeService.addEmployee(testActor, organization.getOrganizationId(), emp.getAccountId());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(organization.getOrganizationId().value())),
                org.bson.Document.class,
                "organizations"
        );
        assertNotNull(rawDoc);
        assertEquals("VERIFIED", rawDoc.getString("verificationStatus"));

        java.util.List<org.bson.Document> members = rawDoc.getList("members", org.bson.Document.class);
        assertNotNull(members);
        assertTrue(members.stream().anyMatch(m -> emp.getAccountId().value().equals(m.getString("accountId"))));
    }

    @Test
    void assignAdministrator_onNeedsMoreInformationOrganization_preservesStatusAndMessage() {
        Account rep = Account.createAccount(new Email("rep.admin@test.com"), new PasswordHash("hash"));
        accountRepository.save(rep);
        Organization organization = createOrganizationService.createOrganization(testActor, OrganizationType.COMPANY, rep.getAccountId());

        Account emp = Account.createAccount(new Email("emp.admin@test.com"), new PasswordHash("hash"));
        accountRepository.save(emp);
        addEmployeeService.addEmployee(testActor, organization.getOrganizationId(), emp.getAccountId());

        mongoTemplate.updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(organization.getOrganizationId().value())),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("verificationStatus", "NEEDS_MORE_INFORMATION")
                        .set("verificationInformationRequest", "Falta el RUT"),
                "organizations"
        );

        assignAdministratorService.assignAdministrator(testActor, organization.getOrganizationId(), emp.getAccountId());

        org.bson.Document rawDoc = mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(organization.getOrganizationId().value())),
                org.bson.Document.class,
                "organizations"
        );
        assertNotNull(rawDoc);
        assertEquals("NEEDS_MORE_INFORMATION", rawDoc.getString("verificationStatus"));
        assertEquals("Falta el RUT", rawDoc.getString("verificationInformationRequest"));
    }
}

