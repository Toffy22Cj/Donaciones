package identity.application.service;

import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import identity.infrastructure.persistence.mongo.BaseMongoIntegrationTest;
import identity.infrastructure.persistence.mongo.repositories.MongoAccountRepositoryAdapter;
import identity.infrastructure.persistence.mongo.repositories.MongoAuditLogAdapter;
import identity.infrastructure.security.BCryptPasswordHasherAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@DataMongoTest
@ContextConfiguration(classes = identity.infrastructure.persistence.mongo.IdentityTestApplication.class)
@Import({
        MongoAccountRepositoryAdapter.class,
        MongoAuditLogAdapter.class,
        BCryptPasswordHasherAdapter.class,
        CreateAccountService.class,
        ChangeCredentialsService.class,
        DeactivateAccountService.class,
        ReactivateAccountService.class
})
class AccountApplicationServiceIntegrationTest extends BaseMongoIntegrationTest {

    @Autowired
    private CreateAccountService createAccountService;

    @Autowired
    private ChangeCredentialsService changeCredentialsService;

    @Autowired
    private DeactivateAccountService deactivateAccountService;

    @Autowired
    private ReactivateAccountService reactivateAccountService;

    @Autowired
    private AccountRepositoryPort accountRepository;

    @Autowired
    private PasswordHasherPort passwordHasher;

    @MockitoSpyBean
    private AuditLogPort auditLogPort;

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
        String password = "password123";

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
        Account account = createAccountService.createAccount(email, "oldPassword");
        org.mockito.Mockito.reset(auditLogPort);

        changeCredentialsService.changeCredentials(account.getAccountId(), "newPassword");

        Account updated = accountRepository.findById(account.getAccountId());
        assertTrue(passwordHasher.matches("newPassword", updated.getPasswordHash()));
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void changeCredentials_onInactiveAccount_throwsException_andDoesNotGenerateAuditLog() {
        Email email = new Email("change.creds.inactive@example.com");
        Account account = createAccountService.createAccount(email, "oldPassword");
        deactivateAccountService.deactivateAccount(account.getAccountId());
        org.mockito.Mockito.reset(auditLogPort);

        assertThrows(identity.domain.exception.InactiveAccountException.class, () -> {
            changeCredentialsService.changeCredentials(account.getAccountId(), "newPassword");
        });

        verify(auditLogPort, never()).record(any());
    }

    @Test
    void deactivateAccount_success() {
        Email email = new Email("deactivate@example.com");
        Account account = createAccountService.createAccount(email, "password");
        org.mockito.Mockito.reset(auditLogPort);

        deactivateAccountService.deactivateAccount(account.getAccountId());

        Account updated = accountRepository.findById(account.getAccountId());
        assertEquals(AccountStatus.INACTIVE, updated.getStatus());
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void deactivateAccount_idempotent_doesNotGenerateAuditLog() {
        Email email = new Email("deactivate.idempotent@example.com");
        Account account = createAccountService.createAccount(email, "password");
        deactivateAccountService.deactivateAccount(account.getAccountId()); // First time mutates
        org.mockito.Mockito.reset(auditLogPort);

        // Second time should be idempotent
        deactivateAccountService.deactivateAccount(account.getAccountId());

        // CRITICAL REQUIREMENT: Explicit negative assertion on idempotent call
        verify(auditLogPort, never()).record(any());
    }

    @Test
    void reactivateAccount_success() {
        Email email = new Email("reactivate@example.com");
        Account account = createAccountService.createAccount(email, "password");
        deactivateAccountService.deactivateAccount(account.getAccountId());
        org.mockito.Mockito.reset(auditLogPort);

        reactivateAccountService.reactivateAccount(account.getAccountId());

        Account updated = accountRepository.findById(account.getAccountId());
        assertEquals(AccountStatus.ACTIVE, updated.getStatus());
        verify(auditLogPort, times(1)).record(any());
    }

    @Test
    void reactivateAccount_idempotent_doesNotGenerateAuditLog() {
        Email email = new Email("reactivate.idempotent@example.com");
        Account account = createAccountService.createAccount(email, "password");
        org.mockito.Mockito.reset(auditLogPort);

        // Already active, so reactivating should be idempotent
        reactivateAccountService.reactivateAccount(account.getAccountId());

        // CRITICAL REQUIREMENT: Explicit negative assertion on idempotent call
        verify(auditLogPort, never()).record(any());
    }
}
